package com.helios.ruleengine.core.compiler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helios.ruleengine.api.IRuleCompiler;
import com.helios.ruleengine.core.evaluation.context.EvaluationContext;
import com.helios.ruleengine.core.model.Dictionary;
import com.helios.ruleengine.core.model.EngineModel;
import com.helios.ruleengine.core.optimization.SmartIsAnyOfFactorizer;
import com.helios.ruleengine.model.Event;
import com.helios.ruleengine.model.Predicate;
import com.helios.ruleengine.model.RuleDefinition;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import it.unimi.dsi.fastutil.ints.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

public class RuleCompiler implements IRuleCompiler {
    private static final Logger logger = Logger.getLogger(RuleCompiler.class.getName());
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Tracer tracer;

    public RuleCompiler(Tracer tracer) {
        this.tracer = tracer;
    }

    public EngineModel compile(Path rulesPath, EngineModel.SelectionStrategy strategy)
            throws IOException, CompilationException {
        Span span = tracer.spanBuilder("compile-rules").startSpan();
        try (Scope scope = span.makeCurrent()) {
            span.setAttribute("ruleFilePath", rulesPath.toString());
            long startTime = System.nanoTime();

            List<RuleDefinition> definitions = loadRules(rulesPath);
            span.setAttribute("logicalRuleCount", definitions.size());

            List<RuleDefinition> validRules = validateAndCanonize(definitions);

            SmartIsAnyOfFactorizer factorizer = new SmartIsAnyOfFactorizer();
            List<RuleDefinition> factoredRules = factorizer.factorize(validRules);

            Dictionary fieldDictionary = new Dictionary();
            Dictionary valueDictionary = new Dictionary();
            buildDictionaries(factoredRules, fieldDictionary, valueDictionary);

            SelectivityProfile profile = profileSelectivity(factoredRules, fieldDictionary, valueDictionary);

            EngineModel.Builder builder = buildCoreModelWithDeduplication(factoredRules, profile, fieldDictionary, valueDictionary);

            long compilationTime = System.nanoTime() - startTime;
            span.setAttribute("compilationTimeMs", TimeUnit.NANOSECONDS.toMillis(compilationTime));

            Map<String, Object> metadata = new HashMap<>();
            int logicalRuleCount = validRules.size();
            int totalExpandedCombinations = builder.getTotalExpandedCombinations();
            int uniqueCombinations = builder.getUniqueCombinationCount();
            double deduplicationRate = totalExpandedCombinations > 0 ?
                    (1.0 - (double) uniqueCombinations / totalExpandedCombinations) * 100 : 0;

            metadata.put("logicalRules", logicalRuleCount);
            metadata.put("totalExpandedCombinations", totalExpandedCombinations);
            metadata.put("uniqueCombinations", uniqueCombinations);
            metadata.put("deduplicationRatePercent", String.format("%.2f", deduplicationRate));

            span.setAttribute("uniqueCombinationCount", uniqueCombinations);
            span.setAttribute("deduplicationRate", String.format("%.2f%%", deduplicationRate));

            EngineModel.EngineStats stats = new EngineModel.EngineStats(
                    uniqueCombinations,
                    builder.getPredicateCount(),
                    compilationTime,
                    metadata
            );

            return builder.withStats(stats)
                    .withFieldDictionary(fieldDictionary)
                    .withValueDictionary(valueDictionary)
                    .withSelectionStrategy(strategy)
                    .build();

        } catch (IOException | CompilationException e) {
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    public EngineModel compile(Path rulesPath) throws IOException, CompilationException {
        return compile(rulesPath, EngineModel.SelectionStrategy.FIRST_MATCH);
    }

    private void buildDictionaries(List<RuleDefinition> definitions, Dictionary fieldDictionary, Dictionary valueDictionary) {
        Span span = tracer.spanBuilder("build-dictionaries").startSpan();
        try (Scope scope = span.makeCurrent()) {
            for (RuleDefinition def : definitions) {
                if (def.conditions() == null) continue;

                for (RuleDefinition.Condition cond : def.conditions()) {
                    if (cond.field() == null) continue;

                    fieldDictionary.encode(cond.field().toUpperCase().replace('-', '_'));

                    if (cond.operator() == null) continue;
                    Predicate.Operator op = Predicate.Operator.fromString(cond.operator());
                    if (op == null) continue;

                    if (op == Predicate.Operator.EQUAL_TO || op == Predicate.Operator.IS_ANY_OF) {
                        if (cond.value() instanceof List) {
                            ((List<?>) cond.value()).forEach(v -> {
                                if (v != null) valueDictionary.encode(String.valueOf(v));
                            });
                        } else if (cond.value() != null) {
                            valueDictionary.encode(String.valueOf(cond.value()));
                        }
                    }
                }
            }
            span.setAttribute("fieldCount", fieldDictionary.size());
            span.setAttribute("valueCount", valueDictionary.size());
        } finally {
            span.end();
        }
    }

    private EngineModel.Builder buildCoreModelWithDeduplication(List<RuleDefinition> definitions,
                                                                SelectivityProfile profile,
                                                                Dictionary fieldDictionary,
                                                                Dictionary valueDictionary) {
        Span span = tracer.spanBuilder("build-core-model").startSpan();
        try (Scope scope = span.makeCurrent()) {
            EngineModel.Builder builder = new EngineModel.Builder();
            for (RuleDefinition def : definitions) {
                List<List<Predicate>> combinations = generatePredicateCombinations(def, profile, fieldDictionary, valueDictionary);
                if (combinations.isEmpty()) continue;

                for (List<Predicate> combination : combinations) {
                    List<Integer> predicateIds = combination.stream()
                            .map(builder::registerPredicate)
                            .collect(Collectors.toList());
                    IntList canonicalKey = new IntArrayList(predicateIds);
                    canonicalKey.sort(null);
                    int combinationId = builder.registerCombination(canonicalKey);
                    builder.addLogicalRuleMapping(def.ruleCode(), def.priority(), def.description(), combinationId);
                }
            }
            return builder;
        } finally {
            span.end();
        }
    }

    // Helper method to check for contradictions
    private boolean hasContradictoryConditions(List<RuleDefinition.Condition> conditions) {
        Map<String, List<RuleDefinition.Condition>> byField = conditions.stream()
                .collect(Collectors.groupingBy(RuleDefinition.Condition::field));

        for (Map.Entry<String, List<RuleDefinition.Condition>> entry : byField.entrySet()) {
            List<RuleDefinition.Condition> fieldConds = entry.getValue();

            // Check EQUAL_TO contradictions
            List<Object> equalToValues = fieldConds.stream()
                    .filter(c -> "EQUAL_TO".equalsIgnoreCase(c.operator()))
                    .map(RuleDefinition.Condition::value)
                    .distinct()
                    .collect(Collectors.toList());

            if (equalToValues.size() > 1) {
                return true;  // Multiple different EQUAL_TO values = contradiction
            }

            // Check IS_ANY_OF with no overlap
            List<Set<Object>> isAnyOfSets = fieldConds.stream()
                    .filter(c -> "IS_ANY_OF".equalsIgnoreCase(c.operator()))
                    .map(c -> new HashSet<Object>((List<?>) c.value()))
                    .collect(Collectors.toList());

            if (isAnyOfSets.size() > 1) {
                Set<Object> intersection = new HashSet<>(isAnyOfSets.get(0));
                for (int i = 1; i < isAnyOfSets.size(); i++) {
                    intersection.retainAll(isAnyOfSets.get(i));
                }
                if (intersection.isEmpty()) {
                    return true;  // No overlap = contradiction
                }
            }

            // Check BETWEEN inverted range
            for (RuleDefinition.Condition cond : fieldConds) {
                if ("BETWEEN".equalsIgnoreCase(cond.operator()) && cond.value() instanceof List) {
                    List<?> range = (List<?>) cond.value();
                    if (range.size() == 2 && range.get(0) instanceof Number && range.get(1) instanceof Number) {
                        double min = ((Number) range.get(0)).doubleValue();
                        double max = ((Number) range.get(1)).doubleValue();
                        if (min > max) {
                            return true;  // Inverted range = contradiction
                        }
                    }
                }
            }
        }

        return false;
    }


    private List<List<Predicate>> generatePredicateCombinations(RuleDefinition def,
                                                                SelectivityProfile profile,
                                                                Dictionary fieldDictionary,
                                                                Dictionary valueDictionary) {
        if (def.conditions() == null || def.conditions().isEmpty()) {
            return new ArrayList<>();
        }

        if (hasContradictoryConditions(def.conditions())) {
            logger.warning("Rule '" + def.ruleCode() + "' has contradictory conditions - skipping");
            return new ArrayList<>();
        }

        List<Predicate> staticPredicates = new ArrayList<>();
        List<List<Predicate>> expandablePredicates = new ArrayList<>();

        for (RuleDefinition.Condition cond : def.conditions()) {
            if (cond.field() == null || cond.operator() == null) continue;

            int fieldId = fieldDictionary.getId(cond.field().toUpperCase().replace('-', '_'));
            Predicate.Operator operator = Predicate.Operator.fromString(cond.operator());
            if (operator == null) continue;

            float selectivity = profile.calculateSelectivity(fieldId, operator, cond.value());
            float weight = (1.0f - selectivity) * profile.getCost(operator);

            if (operator == Predicate.Operator.IS_ANY_OF && cond.value() instanceof List) {
                List<?> values = (List<?>) cond.value();
                List<Predicate> expanded = new ArrayList<>();
                for (Object v : values) {
                    int valueId = valueDictionary.getId(String.valueOf(v));
                    expanded.add(new Predicate(fieldId, Predicate.Operator.EQUAL_TO, valueId, null, weight, selectivity));
                }
                expandablePredicates.add(expanded);
            } else {
                Object predicateValue = (operator == Predicate.Operator.EQUAL_TO && !(cond.value() instanceof Number))
                        ? valueDictionary.getId(String.valueOf(cond.value()))
                        : cond.value();
                Pattern pattern = (operator == Predicate.Operator.REGEX && cond.value() instanceof String)
                        ? Pattern.compile((String) cond.value()) : null;
                staticPredicates.add(new Predicate(fieldId, operator, predicateValue, pattern, weight, selectivity));
            }
        }

        List<List<Predicate>> combinations = new ArrayList<>();
        List<List<Predicate>> expandedParts = generateCombinations(expandablePredicates);

        if (expandedParts.isEmpty()) {
            if (!staticPredicates.isEmpty()) combinations.add(staticPredicates);
        } else {
            for (List<Predicate> expanded : expandedParts) {
                List<Predicate> fullCombination = new ArrayList<>(staticPredicates);
                fullCombination.addAll(expanded);
                combinations.add(fullCombination);
            }
        }
        return combinations;
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

    private List<RuleDefinition> loadRules(Path rulesPath) throws IOException {
        String content = Files.readString(rulesPath);
        return objectMapper.readValue(content, objectMapper.getTypeFactory().constructCollectionType(List.class, RuleDefinition.class));
    }

    private List<RuleDefinition> validateAndCanonize(List<RuleDefinition> definitions) throws CompilationException {
        if (definitions == null || definitions.isEmpty()) {
            throw new CompilationException("Rule definitions cannot be empty");
        }

        Set<String> ruleCodes = new HashSet<>();
        List<RuleDefinition> validated = new ArrayList<>();

        for (int i = 0; i < definitions.size(); i++) {
            RuleDefinition def = definitions.get(i);

            // Validate rule code
            if (def.ruleCode() == null || def.ruleCode().trim().isEmpty()) {
                throw new CompilationException("Rule at index " + i + " has missing or empty rule_code");
            }

            if (ruleCodes.contains(def.ruleCode())) {
                throw new CompilationException("Duplicate rule_code: " + def.ruleCode());
            }
            ruleCodes.add(def.ruleCode());

            // Validate conditions
            if (def.conditions() == null) {
                throw new CompilationException("Rule '" + def.ruleCode() + "' has null conditions");
            }

            if (def.conditions().isEmpty()) {
                // Empty conditions are allowed but will match all events
                validated.add(def);
                continue;
            }

            // Validate each condition
            List<RuleDefinition.Condition> canonizedConditions = new ArrayList<>();
            for (int j = 0; j < def.conditions().size(); j++) {
                RuleDefinition.Condition cond = def.conditions().get(j);

                if (cond.field() == null) {
                    throw new CompilationException("Rule '" + def.ruleCode() + "' condition " + j + " has null field");
                }

                if (cond.operator() == null) {
                    throw new CompilationException("Rule '" + def.ruleCode() + "' condition " + j + " has null operator");
                }

                // Validate operator
                Predicate.Operator operator = Predicate.Operator.fromString(cond.operator());
                if (operator == null) {
                    throw new CompilationException("Rule '" + def.ruleCode() + "' has unknown operator: " + cond.operator());
                }

                // Validate value based on operator
                if (cond.value() == null && operator != Predicate.Operator.IS_NULL && operator != Predicate.Operator.IS_NOT_NULL) {
                    throw new CompilationException("Rule '" + def.ruleCode() + "' condition " + j + " has null value for operator " + operator);
                }

                // Validate IS_ANY_OF and BETWEEN operators
                if (operator == Predicate.Operator.IS_ANY_OF || operator == Predicate.Operator.IS_NONE_OF) {
                    if (!(cond.value() instanceof List)) {
                        throw new CompilationException("Rule '" + def.ruleCode() + "' operator " + operator + " requires array value");
                    }
                    List<?> list = (List<?>) cond.value();
                    if (list.isEmpty()) {
                        throw new CompilationException("Rule '" + def.ruleCode() + "' operator " + operator + " requires non-empty array");
                    }
                }

                if (operator == Predicate.Operator.BETWEEN) {
                    if (!(cond.value() instanceof List)) {
                        throw new CompilationException("Rule '" + def.ruleCode() + "' BETWEEN operator requires array value");
                    }
                    List<?> list = (List<?>) cond.value();
                    if (list.size() != 2) {
                        throw new CompilationException("Rule '" + def.ruleCode() + "' BETWEEN operator requires exactly 2 values, got " + list.size());
                    }
                    // Check for inverted range
                    if (list.get(0) instanceof Number && list.get(1) instanceof Number) {
                        double min = ((Number) list.get(0)).doubleValue();
                        double max = ((Number) list.get(1)).doubleValue();
                        if (min > max) {
                            // This creates a contradiction - return empty combinations
                            logger.warning("Rule '" + def.ruleCode() + "' has inverted BETWEEN range: [" + min + ", " + max + "]");
                        }
                    }
                }

                // Validate numeric operators
                if (operator == Predicate.Operator.GREATER_THAN || operator == Predicate.Operator.GREATER_THAN_OR_EQUAL ||
                        operator == Predicate.Operator.LESS_THAN || operator == Predicate.Operator.LESS_THAN_OR_EQUAL) {
                    if (!(cond.value() instanceof Number)) {
                        throw new CompilationException("Rule '" + def.ruleCode() + "' numeric operator " + operator + " requires numeric value, got: " + cond.value().getClass().getSimpleName());
                    }
                }

                // Validate regex pattern
                if (operator == Predicate.Operator.REGEX) {
                    if (!(cond.value() instanceof String)) {
                        throw new CompilationException("Rule '" + def.ruleCode() + "' REGEX operator requires string value");
                    }
                    try {
                        Pattern.compile((String) cond.value());
                    } catch (PatternSyntaxException e) {
                        throw new CompilationException("Rule '" + def.ruleCode() + "' has invalid regex pattern: " + e.getMessage());
                    }
                }

                // Canonize field name
                String canonField = cond.field().toUpperCase().replace('-', '_');
                canonizedConditions.add(new RuleDefinition.Condition(canonField, cond.operator(), cond.value()));
            }

            // Check for contradictions
            detectContradictions(def.ruleCode(), canonizedConditions);

            // Create canonized rule
            validated.add(new RuleDefinition(
                    def.ruleCode(),
                    canonizedConditions,
                    def.priority(),
                    def.description(),
                    def.enabled()
            ));
        }

        return validated;
    }

    private void detectContradictions(String ruleCode, List<RuleDefinition.Condition> conditions) throws CompilationException {
        // Group conditions by field
        Map<String, List<RuleDefinition.Condition>> byField = conditions.stream()
                .collect(Collectors.groupingBy(RuleDefinition.Condition::field));

        for (Map.Entry<String, List<RuleDefinition.Condition>> entry : byField.entrySet()) {
            List<RuleDefinition.Condition> fieldConditions = entry.getValue();

            // Check for multiple EQUAL_TO on same field
            List<Object> equalToValues = fieldConditions.stream()
                    .filter(c -> "EQUAL_TO".equalsIgnoreCase(c.operator()))
                    .map(RuleDefinition.Condition::value)
                    .distinct()
                    .collect(Collectors.toList());

            if (equalToValues.size() > 1) {
                logger.warning("Rule '" + ruleCode + "' has contradictory EQUAL_TO conditions on field '" + entry.getKey() + "': " + equalToValues);
            }

            // Check for IS_ANY_OF with no overlapping values
            List<Set<Object>> isAnyOfSets = fieldConditions.stream()
                    .filter(c -> "IS_ANY_OF".equalsIgnoreCase(c.operator()))
                    .map(c -> new HashSet<Object>((List<?>) c.value()))
                    .collect(Collectors.toList());

            if (isAnyOfSets.size() > 1) {
                Set<Object> intersection = new HashSet<>(isAnyOfSets.get(0));
                for (int i = 1; i < isAnyOfSets.size(); i++) {
                    intersection.retainAll(isAnyOfSets.get(i));
                }
                if (intersection.isEmpty()) {
                    logger.warning("Rule '" + ruleCode + "' has contradictory IS_ANY_OF conditions on field '" + entry.getKey() + "' with no overlapping values");
                }
            }
        }
    }

    private SelectivityProfile profileSelectivity(List<RuleDefinition> definitions, Dictionary fieldDictionary, Dictionary valueDictionary) {
        return new SelectivityProfile(definitions, fieldDictionary, valueDictionary);
    }


    static class SelectivityProfile {
        private final Int2IntOpenHashMap fieldCounts;
        private final Map<String, Integer> valueCounts;
        private final int totalRules;

        public SelectivityProfile(List<RuleDefinition> definitions, Dictionary fieldDictionary, Dictionary valueDictionary) {
            this.fieldCounts = new Int2IntOpenHashMap();
            this.valueCounts = new HashMap<>();
            this.totalRules = definitions.size();

            for (RuleDefinition def : definitions) {
                if (def.conditions() == null) continue;
                for (RuleDefinition.Condition cond : def.conditions()) {
                    if (cond.field() == null) continue;
                    int fieldId = fieldDictionary.getId(cond.field().toUpperCase().replace('-', '_'));
                    fieldCounts.addTo(fieldId, 1);
                    if (cond.value() != null) {
                        valueCounts.merge(String.valueOf(cond.value()), 1, Integer::sum);
                    }
                }
            }
        }

        public float calculateSelectivity(int fieldId, Predicate.Operator operator, Object value) {
            if (operator == null) return 0.5f;

            int fieldCount = fieldCounts.getOrDefault(fieldId, 1);
            float baseSelectivity = Math.min(1.0f, (float) fieldCount / totalRules);

            return switch (operator) {
                case EQUAL_TO -> baseSelectivity * 0.1f;
                case GREATER_THAN, LESS_THAN -> baseSelectivity * 0.3f;
                case GREATER_THAN_OR_EQUAL, LESS_THAN_OR_EQUAL -> baseSelectivity * 0.35f;
                case IS_ANY_OF -> baseSelectivity * (value instanceof List ? ((List<?>) value).size() * 0.15f : 0.2f);
                case CONTAINS, STARTS_WITH, ENDS_WITH -> baseSelectivity * 0.4f;
                case REGEX -> baseSelectivity * 0.5f;
                default -> 0.5f;
            };
        }

        public float getCost(Predicate.Operator operator) {
            if (operator == null) return 1.0f;

            return switch (operator) {
                case EQUAL_TO -> 1.0f;
                case GREATER_THAN, LESS_THAN, GREATER_THAN_OR_EQUAL, LESS_THAN_OR_EQUAL -> 1.5f;
                case IS_ANY_OF -> 2.0f;
                case CONTAINS, STARTS_WITH, ENDS_WITH -> 3.0f;
                case REGEX -> 10.0f;
                default -> 1.0f;
            };
        }
    }
}