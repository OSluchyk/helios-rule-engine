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
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Phase 4: Enhanced rule compiler with selectivity profiling, weight calculation,
 * and Dictionary Encoding.
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

        // Dictionary Encoding
        Dictionary fieldDictionary = new Dictionary();
        Dictionary valueDictionary = new Dictionary();
        buildDictionaries(validRules, fieldDictionary, valueDictionary);
        logger.info(String.format("Dictionary encoding complete: %d unique fields, %d unique values",
                fieldDictionary.size(), valueDictionary.size()));

        SelectivityProfile profile = profileSelectivity(validRules, fieldDictionary, valueDictionary);
        logger.info("Selectivity profiling complete: " + profile.getSummary());

        EngineModel.Builder builder = buildCoreModel(validRules, profile, fieldDictionary, valueDictionary);

        long compilationTime = System.nanoTime() - startTime;
        logger.info(String.format("Phase 4 Compilation completed in %.2f ms",
                compilationTime / 1_000_000.0));

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

        EngineModel model = builder.withStats(stats)
                .withFieldDictionary(fieldDictionary)
                .withValueDictionary(valueDictionary)
                .build();

        logger.info(String.format("Final Model: %d internal rules, %d unique predicates, avg selectivity: %.3f",
                model.getNumRules(),
                model.getPredicateRegistry().size(),
                profile.getAverageSelectivity()));

        return model;
    }

    private void buildDictionaries(List<RuleDefinition> definitions, Dictionary fieldDictionary, Dictionary valueDictionary) {
        for (RuleDefinition def : definitions) {
            for (RuleDefinition.Condition cond : def.conditions()) {
                fieldDictionary.encode(cond.field().toUpperCase().replace('-', '_'));
                Predicate.Operator op = Predicate.Operator.fromString(cond.operator());
                if (op == Predicate.Operator.EQUAL_TO || op == Predicate.Operator.IS_ANY_OF) {
                    if (cond.value() instanceof List) {
                        ((List<?>) cond.value()).forEach(v -> valueDictionary.encode(String.valueOf(v)));
                    } else {
                        valueDictionary.encode(String.valueOf(cond.value()));
                    }
                }
            }
        }
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

    private SelectivityProfile profileSelectivity(List<RuleDefinition> definitions, Dictionary fieldDictionary, Dictionary valueDictionary) {
        Map<Integer, Set<Integer>> fieldValues = new HashMap<>();
        for (RuleDefinition def : definitions) {
            for (RuleDefinition.Condition cond : def.conditions()) {
                int fieldId = fieldDictionary.getId(cond.field().toUpperCase().replace('-', '_'));
                Predicate.Operator op = Predicate.Operator.fromString(cond.operator());

                if (op == Predicate.Operator.EQUAL_TO) {
                    int valueId = valueDictionary.getId(String.valueOf(cond.value()));
                    if (valueId != -1) {
                        fieldValues.computeIfAbsent(fieldId, k -> new HashSet<>()).add(valueId);
                    }
                } else if (op == Predicate.Operator.IS_ANY_OF && cond.value() instanceof List) {
                    for (Object val : (List<?>) cond.value()) {
                        int valueId = valueDictionary.getId(String.valueOf(val));
                        if (valueId != -1) {
                            fieldValues.computeIfAbsent(fieldId, k -> new HashSet<>()).add(valueId);
                        }
                    }
                }
            }
        }
        return new SelectivityProfile(fieldValues);
    }

    private EngineModel.Builder buildCoreModel(List<RuleDefinition> definitions,
                                               SelectivityProfile profile,
                                               Dictionary fieldDictionary,
                                               Dictionary valueDictionary) {
        EngineModel.Builder builder = new EngineModel.Builder();
        AtomicInteger ruleIdGen = new AtomicInteger(0);

        for (RuleDefinition def : definitions) {
            List<Predicate> staticPredicates = new ArrayList<>();
            List<List<Predicate>> expandablePredicates = new ArrayList<>();

            for (RuleDefinition.Condition cond : def.conditions()) {
                int fieldId = fieldDictionary.getId(cond.field().toUpperCase().replace('-', '_'));
                Predicate.Operator operator = Predicate.Operator.fromString(cond.operator());
                float selectivity = profile.calculateSelectivity(fieldId, operator, cond.value());
                float weight = 1.0f - selectivity;

                if (operator == Predicate.Operator.IS_ANY_OF) {
                    List<?> values = (List<?>) cond.value();
                    if (values.size() == 1) { // Strength Reduction
                        int valueId = valueDictionary.getId(String.valueOf(values.get(0)));
                        staticPredicates.add(new Predicate(fieldId, Predicate.Operator.EQUAL_TO, valueId, null, weight, selectivity));
                    } else {
                        List<Predicate> variants = values.stream()
                                .map(val -> {
                                    int valueId = valueDictionary.getId(String.valueOf(val));
                                    return new Predicate(fieldId, Predicate.Operator.EQUAL_TO, valueId, null, weight, selectivity);
                                })
                                .collect(Collectors.toList());
                        if (!variants.isEmpty()) {
                            expandablePredicates.add(variants);
                        }
                    }
                } else if (operator == Predicate.Operator.EQUAL_TO) {
                    int valueId = valueDictionary.getId(String.valueOf(cond.value()));
                    staticPredicates.add(new Predicate(fieldId, operator, valueId, null, weight, selectivity));
                } else { // Non-encodable operators
                    Pattern pattern = operator == Predicate.Operator.REGEX ? Pattern.compile((String) cond.value()) : null;
                    staticPredicates.add(new Predicate(fieldId, operator, cond.value(), pattern, weight, selectivity));
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
                        new IntArrayList(predicateIds),
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
        private final Map<Integer, Set<Integer>> fieldValues;
        private final Map<Integer, Float> fieldSelectivity;

        SelectivityProfile(Map<Integer, Set<Integer>> fieldValues) {
            this.fieldValues = fieldValues;
            this.fieldSelectivity = new HashMap<>();
            for (Map.Entry<Integer, Set<Integer>> entry : fieldValues.entrySet()) {
                fieldSelectivity.put(entry.getKey(), 1.0f / Math.max(2.0f, (float) entry.getValue().size()));
            }
        }

        float calculateSelectivity(int fieldId, Predicate.Operator operator, Object value) {
            float base = fieldSelectivity.getOrDefault(fieldId, 0.5f);
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