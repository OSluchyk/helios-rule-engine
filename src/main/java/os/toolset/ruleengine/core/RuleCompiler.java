package os.toolset.ruleengine.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import os.toolset.ruleengine.model.Predicate;
import os.toolset.ruleengine.model.Rule;
import os.toolset.ruleengine.model.RuleDefinition;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Compiles rule definitions into an optimized EngineModel, supporting DNF expansion for IS_ANY_OF.
 */
public class RuleCompiler {
    private static final Logger logger = Logger.getLogger(RuleCompiler.class.getName());
    private final ObjectMapper objectMapper = new ObjectMapper();

    public EngineModel compile(Path rulesPath) throws IOException, CompilationException {
        long startTime = System.nanoTime();
        logger.info("Starting rule compilation from: " + rulesPath);

        List<RuleDefinition> definitions = loadRules(rulesPath);
        logger.info("Loaded " + definitions.size() + " rule definitions");

        List<RuleDefinition> validRules = validateAndCanonize(definitions);
        logger.info("Validated " + validRules.size() + " rules are valid and enabled");

        EngineModel.Builder builder = buildCoreModel(validRules);

        long compilationTime = System.nanoTime() - startTime;
        logger.info(String.format("Phase 2 Compilation completed in %.2f ms", compilationTime / 1_000_000.0));

        // Create final stats before building the model
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("avgPredicatesPerLogicalRule",
                validRules.stream()
                        .mapToInt(def -> def.conditions().size())
                        .average()
                        .orElse(0));
        metadata.put("expansionFactor", validRules.isEmpty() ? 0 : (double) builder.ruleStore.size() / validRules.size());

        EngineModel.EngineStats stats = new EngineModel.EngineStats(
                builder.ruleStore.size(),
                builder.getPredicateCount(),
                compilationTime,
                metadata
        );

        // Pass stats to the builder before finalizing the model
        EngineModel model = builder.withStats(stats).build();

        logger.info(String.format("Final Model: %d internal rules, %d unique predicates.",
                model.getRuleStore().length, model.getPredicateRegistry().size()));
        return model;
    }


    private List<RuleDefinition> loadRules(Path rulesPath) throws IOException {
        String content = Files.readString(rulesPath);
        TypeFactory typeFactory = objectMapper.getTypeFactory();
        return objectMapper.readValue(content, typeFactory.constructCollectionType(List.class, RuleDefinition.class));
    }

    private List<RuleDefinition> validateAndCanonize(List<RuleDefinition> definitions) throws CompilationException {
        List<RuleDefinition> valid = new ArrayList<>();
        Set<String> ruleCodes = new HashSet<>();

        for (RuleDefinition def : definitions) {
            if (def.enabled() == null || !def.enabled()) continue;
            if (def.ruleCode() == null || def.ruleCode().isBlank()) {
                throw new CompilationException("Rule must have a non-empty rule_code");
            }
            if (!ruleCodes.add(def.ruleCode())) {
                throw new CompilationException("Duplicate rule_code: " + def.ruleCode());
            }
            if (def.conditions() == null || def.conditions().isEmpty()) {
                throw new CompilationException("Rule " + def.ruleCode() + " must have at least one condition");
            }
            for (RuleDefinition.Condition cond : def.conditions()) {
                if (!"EQUAL_TO".equals(cond.operator()) && !"IS_ANY_OF".equals(cond.operator())) {
                    throw new CompilationException("Operator must be EQUAL_TO or IS_ANY_OF for rule '"
                            + def.ruleCode() + "', but got: " + cond.operator());
                }
                if ("IS_ANY_OF".equals(cond.operator()) && !(cond.value() instanceof List)) {
                    throw new CompilationException("Value for IS_ANY_OF must be a list for rule '" + def.ruleCode() + "'");
                }
            }
            valid.add(def);
        }
        return valid;
    }

    private EngineModel.Builder buildCoreModel(List<RuleDefinition> definitions) {
        EngineModel.Builder builder = new EngineModel.Builder();
        AtomicInteger ruleIdGen = new AtomicInteger(0);
        logger.fine("Starting core model build...");

        for (RuleDefinition def : definitions) {
            logger.finer("Processing logical rule: " + def.ruleCode());
            // 1. Separate static predicates from expandable ones
            List<Predicate> staticPredicates = new ArrayList<>();
            List<List<Predicate>> expandablePredicates = new ArrayList<>();

            for (RuleDefinition.Condition cond : def.conditions()) {
                String normalizedField = cond.field().toUpperCase().replace('-', '_');
                if ("EQUAL_TO".equals(cond.operator())) {
                    staticPredicates.add(new Predicate(normalizedField, cond.value()));
                } else { // IS_ANY_OF
                    List<?> values = (List<?>) cond.value();
                    // Strength reduction: if IS_ANY_OF has one value, treat it as EQUAL_TO
                    if (values.size() == 1) {
                        logger.finer("Performing strength reduction for '" + def.ruleCode() + "' on field '" + cond.field() + "'");
                        staticPredicates.add(new Predicate(normalizedField, values.get(0)));
                    } else {
                        List<Predicate> variants = values.stream()
                                .map(val -> new Predicate(normalizedField, val))
                                .collect(Collectors.toList());
                        if (!variants.isEmpty()) {
                            expandablePredicates.add(variants);
                        }
                    }
                }
            }

            // 2. Generate combinations (Cartesian Product) for DNF expansion
            List<List<Predicate>> combinations = generateCombinations(expandablePredicates);
            if (combinations.isEmpty() && !staticPredicates.isEmpty()) {
                combinations.add(new ArrayList<>()); // Handle case with only static predicates
            }

            if (combinations.size() > 1) {
                logger.info("Expanding rule '" + def.ruleCode() + "' into " + combinations.size() + " internal rules.");
            }

            // 3. Create a rule for each combination
            for (List<Predicate> combination : combinations) {
                List<Predicate> allPredicates = new ArrayList<>(staticPredicates);
                allPredicates.addAll(combination);

                List<Integer> predicateIds = allPredicates.stream()
                        .map(builder::registerPredicate)
                        .collect(Collectors.toList());

                Rule rule = new Rule(
                        ruleIdGen.getAndIncrement(),
                        def.ruleCode(),
                        predicateIds.size(),
                        predicateIds,
                        def.priority(),
                        def.description()
                );
                builder.addRule(rule);
            }
        }
        logger.fine("Core model build finished.");
        return builder;
    }

    private List<List<Predicate>> generateCombinations(List<List<Predicate>> lists) {
        List<List<Predicate>> result = new ArrayList<>();
        if (lists == null || lists.isEmpty()) {
            return result;
        }
        generateCombinationsRecursive(lists, 0, new ArrayList<>(), result);
        return result;
    }

    private void generateCombinationsRecursive(List<List<Predicate>> lists, int index, List<Predicate> current, List<List<Predicate>> result) {
        if (index == lists.size()) {
            result.add(new ArrayList<>(current));
            return;
        }
        for (Predicate p : lists.get(index)) {
            current.add(p);
            generateCombinationsRecursive(lists, index + 1, current, result);
            current.remove(current.size() - 1); // Backtrack
        }
    }

    public static class CompilationException extends Exception {
        public CompilationException(String message) {
            super(message);
        }
    }
}

