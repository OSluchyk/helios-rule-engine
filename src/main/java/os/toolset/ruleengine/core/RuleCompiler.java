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
import java.util.logging.Logger;

/**
 * Compiles rule definitions into an optimized EngineModel.
 */
public class RuleCompiler {
    private static final Logger logger = Logger.getLogger(RuleCompiler.class.getName());
    private final ObjectMapper objectMapper = new ObjectMapper();

    public EngineModel compile(Path rulesPath) throws IOException, CompilationException {
        long startTime = System.nanoTime();

        List<RuleDefinition> definitions = loadRules(rulesPath);
        logger.info("Loaded " + definitions.size() + " rule definitions");

        List<RuleDefinition> validRules = validateAndCanonize(definitions);
        logger.info("Validated " + validRules.size() + " rules");

        // Build the core model structures (predicates, rules, index)
        EngineModel.Builder builder = buildCoreModel(validRules);

        long compilationTime = System.nanoTime() - startTime;

        // Now that all operations are complete, create the final stats
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("avgPredicatesPerRule",
                validRules.stream()
                        .mapToInt(def -> def.conditions().size())
                        .average()
                        .orElse(0));

        EngineModel.EngineStats stats = new EngineModel.EngineStats(
                validRules.size(),
                builder.getPredicateCount(),
                compilationTime, // Use the final, total compilation time
                metadata
        );

        builder.withStats(stats);
        EngineModel model = builder.build();

        logger.info(String.format("Compilation completed in %.2f ms", compilationTime / 1_000_000.0));
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
            if (!def.enabled()) continue;
            if (def.ruleCode() == null || def.ruleCode().isBlank()) {
                throw new CompilationException("Rule must have a non-empty rule_code");
            }
            if (!ruleCodes.add(def.ruleCode())) {
                throw new CompilationException("Duplicate rule_code: " + def.ruleCode());
            }
            if (def.conditions() == null || def.conditions().isEmpty()) {
                throw new CompilationException("Rule " + def.ruleCode() + " must have at least one condition");
            }
            // Restore validation for robustness
            for (RuleDefinition.Condition cond : def.conditions()) {
                if (!"EQUAL_TO".equals(cond.operator())) {
                    throw new CompilationException("MVP only supports EQUAL_TO operator for rule '"
                            + def.ruleCode() + "', but got: " + cond.operator());
                }
                if (cond.field() == null || cond.value() == null) {
                    throw new CompilationException("Condition in rule '" + def.ruleCode()
                            + "' must have a non-null field and value.");
                }
            }
            valid.add(def);
        }
        return valid;
    }

    private EngineModel.Builder buildCoreModel(List<RuleDefinition> definitions) {
        EngineModel.Builder builder = new EngineModel.Builder();
        AtomicInteger ruleIdGen = new AtomicInteger(0);

        Map<RuleDefinition, List<Integer>> ruleToPredicateIds = new HashMap<>();

        // First pass: Register all unique predicates
        for (RuleDefinition def : definitions) {
            List<Integer> predicateIds = new ArrayList<>();
            // Corrected loop to iterate over a List of Condition objects
            for (RuleDefinition.Condition condition : def.conditions()) {
                Predicate predicate = new Predicate(condition.field(), condition.value());
                int predicateId = builder.registerPredicate(predicate);
                predicateIds.add(predicateId);
            }
            ruleToPredicateIds.put(def, predicateIds);
        }

        // Second pass: Create rules and build the inverted index
        for (RuleDefinition def : definitions) {
            List<Integer> predicateIds = ruleToPredicateIds.get(def);
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

        return builder;
    }

    public static class CompilationException extends Exception {
        public CompilationException(String message) {
            super(message);
        }
    }
}

