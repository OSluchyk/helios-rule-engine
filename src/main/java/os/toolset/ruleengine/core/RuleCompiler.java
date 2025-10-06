package os.toolset.ruleengine.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import os.toolset.ruleengine.core.optimization.SmartIsAnyOfFactorizer;
import os.toolset.ruleengine.model.Predicate;
import os.toolset.ruleengine.model.RuleDefinition;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class RuleCompiler {
    private static final Logger logger = Logger.getLogger(RuleCompiler.class.getName());
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Tracer tracer;

    public RuleCompiler(Tracer tracer) {
        this.tracer = tracer;
    }

    public EngineModel compile(Path rulesPath) throws IOException, CompilationException {
        Span span = tracer.spanBuilder("compile-rules").startSpan();
        try (Scope scope = span.makeCurrent()) {
            span.setAttribute("ruleFilePath", rulesPath.toString());
            long startTime = System.nanoTime();

            List<RuleDefinition> definitions = loadRules(rulesPath);
            span.setAttribute("logicalRuleCount", definitions.size());

            List<RuleDefinition> validRules = validateAndCanonize(definitions);

            // Apply Smart IS_ANY_OF Factoring
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
            double deduplicationRate = totalExpandedCombinations > 0 ? (1.0 - (double) uniqueCombinations / totalExpandedCombinations) * 100 : 0;

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
                    .build();

        } catch (IOException | CompilationException e) {
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    private void buildDictionaries(List<RuleDefinition> definitions, Dictionary fieldDictionary, Dictionary valueDictionary) {
        Span span = tracer.spanBuilder("build-dictionaries").startSpan();
        try (Scope scope = span.makeCurrent()) {
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
        try(Scope scope = span.makeCurrent()) {
            EngineModel.Builder builder = new EngineModel.Builder();
            for (RuleDefinition def : definitions) {
                List<List<Predicate>> combinations = generatePredicateCombinations(def, profile, fieldDictionary, valueDictionary);
                if (combinations.isEmpty()) continue;
                for (List<Predicate> combination : combinations) {
                    List<Integer> predicateIds = combination.stream().map(builder::registerPredicate).collect(Collectors.toList());
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

    private List<List<Predicate>> generatePredicateCombinations(RuleDefinition def,
                                                                SelectivityProfile profile,
                                                                Dictionary fieldDictionary,
                                                                Dictionary valueDictionary) {
        List<Predicate> staticPredicates = new ArrayList<>();
        List<List<Predicate>> expandablePredicates = new ArrayList<>();

        for (RuleDefinition.Condition cond : def.conditions()) {
            int fieldId = fieldDictionary.getId(cond.field().toUpperCase().replace('-', '_'));
            Predicate.Operator operator = Predicate.Operator.fromString(cond.operator());
            float selectivity = profile.calculateSelectivity(fieldId, operator, cond.value());
            // FIX: Weight should be directly proportional to selectivity.
            // Sorting is ascending on weight, so the predicates with the lowest selectivity
            // (most rare, most efficient to check first) will appear first in the sorted list.
            float weight = selectivity;

            if (operator == Predicate.Operator.IS_ANY_OF) {
                List<?> values = (List<?>) cond.value();
                if (values.size() == 1) {
                    int valueId = valueDictionary.getId(String.valueOf(values.get(0)));
                    staticPredicates.add(new Predicate(fieldId, Predicate.Operator.EQUAL_TO, valueId, null, weight, selectivity));
                } else {
                    List<Predicate> variants = values.stream()
                            .map(val -> new Predicate(fieldId, Predicate.Operator.EQUAL_TO, valueDictionary.getId(String.valueOf(val)), null, weight, selectivity))
                            .collect(Collectors.toList());
                    if (!variants.isEmpty()) expandablePredicates.add(variants);
                }
            } else if (operator == Predicate.Operator.EQUAL_TO) {
                int valueId = valueDictionary.getId(String.valueOf(cond.value()));
                staticPredicates.add(new Predicate(fieldId, operator, valueId, null, weight, selectivity));
            } else {
                Pattern pattern = operator == Predicate.Operator.REGEX ? Pattern.compile((String) cond.value()) : null;
                staticPredicates.add(new Predicate(fieldId, operator, cond.value(), pattern, weight, selectivity));
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
        // Validation logic...
        return definitions;
    }

    private SelectivityProfile profileSelectivity(List<RuleDefinition> definitions, Dictionary fieldDictionary, Dictionary valueDictionary) {
        return new SelectivityProfile(definitions, fieldDictionary, valueDictionary);
    }

    /**
     * Phase 4 Optimization: Calculates predicate selectivity to inform evaluation order.
     * Predicates that are more selective (less likely to be true) are evaluated first.
     */
    private static class SelectivityProfile {
        private final Map<Integer, Int2IntOpenHashMap> valueFrequencies = new HashMap<>();
        private final Int2IntOpenHashMap fieldCounts = new Int2IntOpenHashMap();
        private final Dictionary valueDictionary;

        SelectivityProfile(List<RuleDefinition> definitions, Dictionary fieldDictionary, Dictionary valueDictionary) {
            this.valueDictionary = valueDictionary;
            for (RuleDefinition def : definitions) {
                for (RuleDefinition.Condition cond : def.conditions()) {
                    int fieldId = fieldDictionary.getId(cond.field().toUpperCase().replace('-', '_'));
                    if (fieldId == -1) continue;

                    fieldCounts.addTo(fieldId, 1);
                    Predicate.Operator op = Predicate.Operator.fromString(cond.operator());

                    if (op == Predicate.Operator.EQUAL_TO) {
                        int valueId = valueDictionary.getId(String.valueOf(cond.value()));
                        if (valueId != -1) {
                            valueFrequencies.computeIfAbsent(fieldId, k -> new Int2IntOpenHashMap()).addTo(valueId, 1);
                        }
                    } else if (op == Predicate.Operator.IS_ANY_OF && cond.value() instanceof List) {
                        ((List<?>) cond.value()).forEach(v -> {
                            int valueId = valueDictionary.getId(String.valueOf(v));
                            if (valueId != -1) {
                                valueFrequencies.computeIfAbsent(fieldId, k -> new Int2IntOpenHashMap()).addTo(valueId, 1);
                            }
                        });
                    }
                }
            }
        }

        float calculateSelectivity(int fieldId, Predicate.Operator operator, Object value) {
            int total = fieldCounts.getOrDefault(fieldId, 0);
            if (total == 0) return 0.5f; // Default for unknown fields

            return switch (operator) {
                case EQUAL_TO -> {
                    Int2IntOpenHashMap freqs = valueFrequencies.get(fieldId);
                    if (freqs == null) yield 0.5f;
                    int valueId = valueDictionary.getId(String.valueOf(value));
                    yield (float) freqs.getOrDefault(valueId, 0) / total;
                }
                case IS_ANY_OF -> {
                    if (value instanceof List) {
                        Int2IntOpenHashMap freqs = valueFrequencies.get(fieldId);
                        if (freqs == null) yield 0.5f;
                        double sum = ((List<?>) value).stream()
                                .mapToInt(v -> valueDictionary.getId(String.valueOf(v)))
                                .mapToDouble(valueId -> (float) freqs.getOrDefault(valueId, 0) / total)
                                .sum();
                        yield (float) Math.min(1.0, sum); // Cap at 1.0
                    }
                    yield 0.5f;
                }
                // Heuristics for non-equality operators
                case GREATER_THAN, LESS_THAN, BETWEEN -> 0.3f;
                case CONTAINS, REGEX -> 0.1f;
                default -> 0.5f;
            };
        }
    }

    public static class CompilationException extends Exception {
        public CompilationException(String message) { super(message); }
    }
}