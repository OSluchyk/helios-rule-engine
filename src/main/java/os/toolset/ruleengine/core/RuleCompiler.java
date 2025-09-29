package os.toolset.ruleengine.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import os.toolset.ruleengine.model.Predicate;
import os.toolset.ruleengine.model.Rule;
import os.toolset.ruleengine.model.RuleDefinition;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Phase 4: Enhanced rule compiler with selectivity profiling and weight calculation.
 * Compiles rule definitions into an optimized EngineModel using SoA layout.
 */
public class RuleCompiler {
    private static final Logger logger = Logger.getLogger(RuleCompiler.class.getName());
    private final ObjectMapper objectMapper = new ObjectMapper();

    public EngineModel compile(Path rulesPath) throws IOException, CompilationException {
        long startTime = System.nanoTime();
        logger.info("Starting Phase 4 rule compilation from: " + rulesPath);

        List<RuleDefinition> definitions = loadRules(rulesPath);
        logger.info("Loaded " + definitions.size() + " rule definitions");

        List<RuleDefinition> validRules = validateAndCanonize(definitions);
        logger.info("Validated " + validRules.size() + " rules are valid and enabled");

        // Phase 4: Profile selectivity before building model
        SelectivityProfile profile = profileSelectivity(validRules);
        logger.info("Selectivity profiling complete: " + profile.getSummary());

        EngineModel.Builder builder = buildCoreModel(validRules, profile);

        long compilationTime = System.nanoTime() - startTime;
        logger.info(String.format("Phase 4 Compilation completed in %.2f ms",
                compilationTime / 1_000_000.0));

        // Create final stats before building the model
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("avgPredicatesPerLogicalRule",
                validRules.stream()
                        .mapToInt(def -> def.conditions().size())
                        .average()
                        .orElse(0));
        metadata.put("expansionFactor",
                validRules.isEmpty() ? 0 : (double) builder.ruleStore.size() / validRules.size());
        metadata.put("avgSelectivity", profile.getAverageSelectivity());
        metadata.put("uniqueFieldCount", profile.getUniqueFieldCount());

        EngineModel.EngineStats stats = new EngineModel.EngineStats(
                builder.ruleStore.size(),
                builder.getPredicateCount(),
                compilationTime,
                metadata
        );

        // Pass stats to the builder before finalizing the model
        EngineModel model = builder.withStats(stats).build();

        logger.info(String.format("Final Model: %d internal rules, %d unique predicates, avg selectivity: %.3f",
                model.getNumRules(),
                model.getPredicateRegistry().size(),
                profile.getAverageSelectivity()));

        return model;
    }

    private List<RuleDefinition> loadRules(Path rulesPath) throws IOException {
        String content = Files.readString(rulesPath);
        TypeFactory typeFactory = objectMapper.getTypeFactory();
        return objectMapper.readValue(content,
                typeFactory.constructCollectionType(List.class, RuleDefinition.class));
    }

    private List<RuleDefinition> validateAndCanonize(List<RuleDefinition> definitions)
            throws CompilationException {
        List<RuleDefinition> valid = new ArrayList<>();

        for (RuleDefinition def : definitions) {
            if (def.enabled() == null || !def.enabled()) continue;
            if (def.ruleCode() == null || def.ruleCode().isBlank()) {
                throw new CompilationException("Rule must have a non-empty rule_code");
            }
            if (def.conditions() == null || def.conditions().isEmpty()) {
                throw new CompilationException("Rule " + def.ruleCode() + " must have at least one condition");
            }

            for (RuleDefinition.Condition cond : def.conditions()) {
                Predicate.Operator op = Predicate.Operator.fromString(cond.operator());
                if (op == null) {
                    throw new CompilationException("Unsupported operator '" + cond.operator() +
                            "' in rule '" + def.ruleCode() + "'");
                }
                if (op == Predicate.Operator.IS_ANY_OF && !(cond.value() instanceof List)) {
                    throw new CompilationException("Value for IS_ANY_OF must be a list for rule '" +
                            def.ruleCode() + "'");
                }
            }
            valid.add(def);
        }
        return valid;
    }

    private SelectivityProfile profileSelectivity(List<RuleDefinition> definitions) {
        Map<String, Set<Object>> fieldValues = new HashMap<>();
        for (RuleDefinition def : definitions) {
            for (RuleDefinition.Condition cond : def.conditions()) {
                String field = cond.field().toUpperCase().replace('-', '_');
                Predicate.Operator op = Predicate.Operator.fromString(cond.operator());

                if (op == Predicate.Operator.EQUAL_TO) {
                    fieldValues.computeIfAbsent(field, k -> new HashSet<>()).add(cond.value());
                } else if (op == Predicate.Operator.IS_ANY_OF && cond.value() instanceof List) {
                    fieldValues.computeIfAbsent(field, k -> new HashSet<>()).addAll((List<?>) cond.value());
                }
            }
        }
        return new SelectivityProfile(fieldValues);
    }

    private EngineModel.Builder buildCoreModel(List<RuleDefinition> definitions,
                                               SelectivityProfile profile) {
        EngineModel.Builder builder = new EngineModel.Builder();
        AtomicInteger ruleIdGen = new AtomicInteger(0);

        for (RuleDefinition def : definitions) {
            List<Predicate> staticPredicates = new ArrayList<>();
            List<List<Predicate>> expandablePredicates = new ArrayList<>();

            for (RuleDefinition.Condition cond : def.conditions()) {
                String normalizedField = cond.field().toUpperCase().replace('-', '_');
                Predicate.Operator operator = Predicate.Operator.fromString(cond.operator());

                if (operator == Predicate.Operator.IS_ANY_OF) {
                    List<?> values = (List<?>) cond.value();
                    if (values.size() == 1) { // Strength Reduction
                        float selectivity = profile.calculateSelectivity(normalizedField, Predicate.Operator.EQUAL_TO, values.get(0));
                        staticPredicates.add(new Predicate(normalizedField, Predicate.Operator.EQUAL_TO, values.get(0), 0, selectivity));
                    } else {
                        List<Predicate> variants = values.stream()
                                .map(val -> {
                                    float selectivity = profile.calculateSelectivity(normalizedField, Predicate.Operator.EQUAL_TO, val);
                                    return new Predicate(normalizedField, Predicate.Operator.EQUAL_TO, val, 0, selectivity);
                                })
                                .collect(Collectors.toList());
                        if (!variants.isEmpty()) {
                            expandablePredicates.add(variants);
                        }
                    }
                } else { // All other operators are static
                    float selectivity = profile.calculateSelectivity(normalizedField, operator, cond.value());
                    staticPredicates.add(new Predicate(normalizedField, operator, cond.value(), 0, selectivity));
                }
            }

            List<List<Predicate>> combinations = generateCombinations(expandablePredicates);
            if (combinations.isEmpty() && !staticPredicates.isEmpty()) {
                combinations.add(new ArrayList<>());
            }

            if (combinations.size() > 1) {
                logger.info("Expanding rule '" + def.ruleCode() + "' into " + combinations.size() + " internal rules.");
            }

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
                        new IntArrayList(predicateIds), // Use fastutil list
                        def.priority(),
                        def.description()
                );
                builder.addRule(rule);
            }
        }
        return builder;
    }

    private List<List<Predicate>> generateCombinations(List<List<Predicate>> lists) {
        List<List<Predicate>> result = new ArrayList<>();
        if (lists.isEmpty()) return result;
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
            current.remove(current.size() - 1);
        }
    }

    private static class SelectivityProfile {
        private final Map<String, Set<Object>> fieldValues;
        private final Map<String, Float> fieldSelectivity;

        SelectivityProfile(Map<String, Set<Object>> fieldValues) {
            this.fieldValues = fieldValues;
            this.fieldSelectivity = new HashMap<>();
            for (Map.Entry<String, Set<Object>> entry : fieldValues.entrySet()) {
                fieldSelectivity.put(entry.getKey(), 1.0f / Math.max(2.0f, (float) entry.getValue().size()));
            }
        }

        float calculateSelectivity(String field, Predicate.Operator operator, Object value) {
            float base = fieldSelectivity.getOrDefault(field, 0.5f);
            float adjustment = switch (operator) {
                case EQUAL_TO -> 1.0f;
                case GREATER_THAN, LESS_THAN -> 2.0f;
                case BETWEEN -> 1.5f;
                case CONTAINS -> 1.2f;
                case REGEX -> 1.1f;
                case IS_ANY_OF -> 1.3f;
            };
            return Math.max(0.01f, Math.min(0.99f, base * adjustment));
        }

        float getAverageSelectivity() {
            if (fieldSelectivity.isEmpty()) return 0.5f;
            return (float) fieldSelectivity.values().stream().mapToDouble(Float::doubleValue).average().orElse(0.5);
        }

        int getUniqueFieldCount() {
            return fieldValues.size();
        }

        String getSummary() {
            return String.format("%d unique fields, avg selectivity: %.3f", getUniqueFieldCount(), getAverageSelectivity());
        }
    }

    public static class CompilationException extends Exception {
        public CompilationException(String message) {
            super(message);
        }
    }
}

