package com.helios.ruleengine.core.compiler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helios.ruleengine.api.IRuleCompiler;
import com.helios.ruleengine.core.model.Dictionary;
import com.helios.ruleengine.core.model.EngineModel;
import com.helios.ruleengine.core.optimization.SmartIsAnyOfFactorizer; // IMPORT ADDED
import com.helios.ruleengine.model.Predicate;
import com.helios.ruleengine.model.RuleDefinition;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;

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

    /**
     * Compiles rules from a JSON file path into an executable EngineModel.
     *
     * The compilation process involves several key steps:
     * 1. Loading and parsing the JSON rule definitions.
     * 2. Validating the syntax and logic of each rule.
     * 3. Applying compile-time optimizations, such as factoring common IS_ANY_OF subsets
     * to improve predicate sharing and deduplication.
     * 4. Building dictionaries to encode string fields and values into integers.
     * 5. Profiling rule selectivity to optimize runtime evaluation order.
     * 6. Building the core model, which involves expanding rule combinations,
     * deduplicating identical combinations, and creating the inverted index.
     *
     * @param rulesPath Path to the JSON rules file.
     * @param strategy The selection strategy (e.g., FIRST_MATCH) for the engine.
     * @return A compiled, executable EngineModel.
     * @throws IOException If the rules file cannot be read.
     * @throws CompilationException If the rules are invalid or a compilation error occurs.
     */
    public EngineModel compile(Path rulesPath, EngineModel.SelectionStrategy strategy)
            throws IOException, CompilationException {
        Span span = tracer.spanBuilder("compile-rules").startSpan();
        try (Scope scope = span.makeCurrent()) {
            span.setAttribute("ruleFilePath", rulesPath.toString());
            long startTime = System.nanoTime();

            List<RuleDefinition> definitions = loadRules(rulesPath);
            span.setAttribute("logicalRuleCount", definitions.size());

            List<RuleDefinition> validRules = validateAndCanonize(definitions);

            // --- FIX APPLIED: Re-integrated SmartIsAnyOfFactorizer ---
            // Apply compile-time rule optimizations, starting with IS_ANY_OF factorization.
            // This rewrites rules that share common signatures (non-IS_ANY_OF conditions)
            // to share common IS_ANY_OF predicate subsets. This significantly
            // improves rule combination deduplication and runtime cache hit rates.
            SmartIsAnyOfFactorizer factorizer = new SmartIsAnyOfFactorizer();
            List<RuleDefinition> factoredRules = factorizer.factorize(validRules);
            span.setAttribute("factoredRuleCount", factoredRules.size());
            // --- END FIX ---

            Dictionary fieldDictionary = new Dictionary();
            Dictionary valueDictionary = new Dictionary();
            buildDictionaries(factoredRules, fieldDictionary, valueDictionary);

            SelectivityProfile profile = profileSelectivity(factoredRules, fieldDictionary, valueDictionary);

            EngineModel.Builder builder = buildCoreModelWithDeduplication(factoredRules, profile, fieldDictionary, valueDictionary);

            long compilationTime = System.nanoTime() - startTime;
            span.setAttribute("compilationTimeMs", TimeUnit.NANOSECONDS.toMillis(compilationTime));

            Map<String, Object> metadata = new HashMap<>();
            int logicalRuleCount = validRules.size(); // Report count *before* factorization
            int totalExpandedCombinations = builder.getTotalExpandedCombinations();
            int uniqueCombinations = builder.getUniqueCombinationCount();
            double deduplicationRate = totalExpandedCombinations > 0 ?
                    (1.0 - (double) uniqueCombinations / totalExpandedCombinations) * 100 : 0;

            metadata.put("logicalRules", logicalRuleCount);
            metadata.put("totalExpandedCombinations", totalExpandedCombinations);
            metadata.put("uniqueCombinations", uniqueCombinations);
            metadata.put("deduplicationRatePercent", String.format("%.2f", deduplicationRate));

            span.setAttribute("uniqueCombinationCount", uniqueCombinations);
            // Note: This attribute name seems like a typo, but we keep it for consistency.
            // It likely should be "deduplicationRatePercent".
            metadata.put("deduplicationRatePercent", deduplicationRate);

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

                    // Encode field name (uppercase and normalize)
                    fieldDictionary.encode(cond.field().toUpperCase().replace('-', '_'));

                    if (cond.operator() == null) continue;
                    Predicate.Operator op = Predicate.Operator.fromString(cond.operator());
                    if (op == null) continue;

                    // Encode ALL string values, uppercasing them for case-insensitive matching
                    if (cond.value() instanceof List) {
                        // Handle list values (IS_ANY_OF, BETWEEN, etc.)
                        ((List<?>) cond.value()).forEach(v -> {
                            if (v instanceof String) {
                                // Uppercase string values for consistency
                                valueDictionary.encode(((String) v).toUpperCase());
                            }
                        });
                    } else if (cond.value() instanceof String) {
                        // Only encode strings for relevant operators
                        // (e.g., don't encode a REGEX pattern string as a value)
                        if (op == Predicate.Operator.EQUAL_TO ||
                                op == Predicate.Operator.NOT_EQUAL_TO ||
                                op == Predicate.Operator.IS_ANY_OF ||
                                op == Predicate.Operator.IS_NONE_OF ||
                                op == Predicate.Operator.CONTAINS ||
                                op == Predicate.Operator.STARTS_WITH ||
                                op == Predicate.Operator.ENDS_WITH) {
                            // Uppercase string values for consistency
                            valueDictionary.encode(((String) cond.value()).toUpperCase());
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
                if (!def.enabled()) continue; // Skip disabled rules
                List<List<Predicate>> combinations = generatePredicateCombinations(def, profile, fieldDictionary, valueDictionary);
                if (combinations.isEmpty()) continue;

                for (List<Predicate> combination : combinations) {
                    List<Integer> predicateIds = combination.stream()
                            .map(builder::registerPredicate)
                            .collect(Collectors.toList());
                    IntList canonicalKey = new IntArrayList(predicateIds);
                    canonicalKey.sort(null); // Sort IDs to create a canonical key
                    int combinationId = builder.registerCombination(canonicalKey);
                    // Map this unique combination back to the original logical rule
                    builder.addLogicalRuleMapping(def.ruleCode(), def.priority(), def.description(), combinationId);
                }
            }
            return builder;
        } finally {
            span.end();
        }
    }

    /**
     * Check for contradictory conditions in a rule.
     * <p>
     * This performs a pre-check before combination generation to avoid building
     * impossible-to-match rule logic.
     * <p>
     * Detects:
     * 1. Multiple different EQUAL_TO values on same field (e.g., status == "A" AND status == "B")
     * 2. IS_ANY_OF operators with no overlap on same field (e.g., country IN [US] AND country IN [CA])
     * 3. BETWEEN with inverted range [max, min] (e.g., amount BETWEEN 100 AND 50)
     * 4. Numeric range contradictions (e.g., x > 1000 AND x < 500)
     */
    private boolean hasContradictoryConditions(List<RuleDefinition.Condition> conditions) {
        // Group conditions by their canonical field name
        Map<String, List<RuleDefinition.Condition>> byField = conditions.stream()
                .collect(Collectors.groupingBy(c -> c.field().toUpperCase().replace('-', '_')));

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

            // Check numeric range contradictions
            // Track the tightest bounds for each direction
            Double minGT = null;   // Minimum value from GREATER_THAN (>)
            Double minGTE = null;  // Minimum value from GREATER_THAN_OR_EQUAL (≥)
            Double maxLT = null;   // Maximum value from LESS_THAN (<)
            Double maxLTE = null;  // Maximum value from LESS_THAN_OR_EQUAL (≤)

            for (RuleDefinition.Condition cond : fieldConds) {
                if (cond.value() == null || !(cond.value() instanceof Number)) continue;
                double val = ((Number) cond.value()).doubleValue();

                String operator = cond.operator().toUpperCase();
                switch (operator) {
                    case "GREATER_THAN":
                        minGT = (minGT == null) ? val : Math.max(minGT, val);
                        break;
                    case "GREATER_THAN_OR_EQUAL":
                        minGTE = (minGTE == null) ? val : Math.max(minGTE, val);
                        break;
                    case "LESS_THAN":
                        maxLT = (maxLT == null) ? val : Math.min(maxLT, val);
                        break;
                    case "LESS_THAN_OR_EQUAL":
                        maxLTE = (maxLTE == null) ? val : Math.min(maxLTE, val);
                        break;
                }
            }

            // Detect impossible ranges
            // x > a AND x < b is impossible if a >= b
            if (minGT != null && maxLT != null && minGT >= maxLT) {
                return true;
            }
            // x > a AND x <= b is impossible if a >= b
            if (minGT != null && maxLTE != null && minGT >= maxLTE) {
                return true;
            }
            // x >= a AND x < b is impossible if a >= b
            if (minGTE != null && maxLT != null && minGTE >= maxLT) {
                return true;
            }
            // x >= a AND x <= b is impossible if a > b
            if (minGTE != null && maxLTE != null && minGTE > maxLTE) {
                return true;
            }
        }

        return false;
    }

    /**
     * Generates all predicate combinations for a single rule definition.
     * This method is responsible for handling the "expansion" of IS_ANY_OF operators.
     *
     * Example: A rule with { status == "A", country IN [US, CA] }
     *
     * 1. Static Predicates: { status == "A" }
     * 2. Expandable Predicates: [ { country == "US" }, { country == "CA" } ]
     * 3. Resulting Combinations:
     * - { status == "A", country == "US" }
     * - { status == "A", country == "CA" }
     *
     * Each of these combinations is then registered with the EngineModel builder.
     */
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

            // Calculate selectivity and weight for runtime optimization
            float selectivity = profile.calculateSelectivity(fieldId, operator, cond.value());
            float weight = (1.0f - selectivity) * profile.getCost(operator);

            if (operator == Predicate.Operator.IS_ANY_OF && cond.value() instanceof List) {
                // This is an "expandable" list.
                // It will be converted into a list of EQUAL_TO predicates.
                List<?> values = (List<?>) cond.value();
                List<Predicate> expanded = new ArrayList<>();
                for (Object v : values) {
                    Object processedValue = v;
                    if (v instanceof String) {
                        // Uppercase string value before dictionary lookup
                        processedValue = valueDictionary.getId(((String) v).toUpperCase());
                    }
                    // Create an EQUAL_TO predicate for each value in the list
                    expanded.add(new Predicate(fieldId, Predicate.Operator.EQUAL_TO, processedValue, null, weight, selectivity));
                }
                if (!expanded.isEmpty()) {
                    expandablePredicates.add(expanded);
                }
            } else {
                // This is a "static" predicate.
                Object predicateValue;
                // Process and dictionary-encode string values based on operator
                if ((operator == Predicate.Operator.EQUAL_TO || operator == Predicate.Operator.NOT_EQUAL_TO)
                        && cond.value() instanceof String) {
                    String stringValue = ((String) cond.value()).toUpperCase();
                    predicateValue = valueDictionary.getId(stringValue);
                } else if (operator == Predicate.Operator.REGEX && cond.value() instanceof String) {
                    // Do not dictionary-encode regex patterns. Store AS-IS.
                    predicateValue = cond.value();
                } else if ((operator == Predicate.Operator.CONTAINS ||
                        operator == Predicate.Operator.STARTS_WITH ||
                        operator == Predicate.Operator.ENDS_WITH)
                        && cond.value() instanceof String) {
                    // Store the raw (uppercased) string value for substring matching.
                    predicateValue = ((String) cond.value()).toUpperCase();
                } else if (cond.value() instanceof String) {
                    // Fallback for other string operators: encode them.
                    predicateValue = valueDictionary.getId(((String) cond.value()).toUpperCase());
                } else {
                    // This handles numbers, booleans, and other non-string types
                    predicateValue = cond.value();
                }

                // Pre-compile regex patterns
                Pattern pattern = (operator == Predicate.Operator.REGEX && cond.value() instanceof String)
                        ? Pattern.compile((String) cond.value()) : null;

                staticPredicates.add(new Predicate(fieldId, operator, predicateValue, pattern, weight, selectivity));
            }
        }

        // Now, generate the Cartesian product of all combinations
        List<List<Predicate>> combinations = new ArrayList<>();
        List<List<Predicate>> expandedParts = generateCombinations(expandablePredicates);

        if (expandedParts.isEmpty()) {
            // No expandable predicates, just add the static list
            if (!staticPredicates.isEmpty()) combinations.add(staticPredicates);
        } else {
            // Add static predicates to each expanded combination
            for (List<Predicate> expanded : expandedParts) {
                List<Predicate> fullCombination = new ArrayList<>(staticPredicates);
                fullCombination.addAll(expanded);
                combinations.add(fullCombination);
            }
        }
        return combinations;
    }

    /**
     * Helper to generate the Cartesian product of predicate lists.
     */
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
        if (lists.get(index).isEmpty()) {
            // Handle case where an IS_ANY_OF list was empty
            generateCombinationsRecursive(lists, index + 1, current, result);
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

    /**
     * Validates a list of rule definitions and canonizes field names.
     *
     * This step ensures that all rules are well-formed before they enter
     * the optimization and compilation pipeline.
     *
     * Validations include:
     * - Checking for null or duplicate rule_codes.
     * - Ensuring conditions, fields, and operators are not null.
     * - Validating operator names.
     * - Validating value types required by specific operators (e.g., IS_ANY_OF, BETWEEN).
     * - Validating REGEX patterns for syntax.
     *
     * It also canonizes field names (to_uppercase, replace '-' with '_')
     * and logs warnings for potential contradictions (which are fully checked later).
     */
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
                // Empty conditions are allowed (will match all events)
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

                // Validate IS_ANY_OF and IS_NONE_OF
                if (operator == Predicate.Operator.IS_ANY_OF || operator == Predicate.Operator.IS_NONE_OF) {
                    if (!(cond.value() instanceof List)) {
                        throw new CompilationException("Rule '" + def.ruleCode() + "' operator " + operator + " requires array value");
                    }
                    List<?> list = (List<?>) cond.value();
                    if (list.isEmpty()) {
                        throw new CompilationException("Rule '" + def.ruleCode() + "' operator " + operator + " cannot have empty array value.");
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
                            logger.warning("Rule '" + def.ruleCode() + "' has inverted BETWEEN range: [" + min + ", " + max + "]");
                        }
                    }
                }

                // Validate numeric operators
                if (operator == Predicate.Operator.GREATER_THAN || operator == Predicate.Operator.GREATER_THAN_OR_EQUAL ||
                        operator == Predicate.Operator.LESS_THAN || operator == Predicate.Operator.LESS_THAN_OR_EQUAL) {
                    if (cond.value() != null && !(cond.value() instanceof Number)) {
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

            // Perform an early check for *obvious* contradictions and log warnings
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

    /**
     * Detects and logs warnings for obvious contradictions.
     * Note: This is a warning-only step. A more comprehensive check
     * happens in `hasContradictoryConditions` before combination generation.
     */
    private void detectContradictions(String ruleCode, List<RuleDefinition.Condition> conditions) {
        // Group conditions by field
        Map<String, List<RuleDefinition.Condition>> byField = conditions.stream()
                .collect(Collectors.groupingBy(c -> c.field().toUpperCase().replace('-', '_')));

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


    /**
     * Profiles the selectivity (how "rare" a predicate is) and cost
     * (how "expensive" an operator is) to calculate an evaluation weight.
     *
     * Predicates with *lower* weights are evaluated first at runtime,
     * allowing the engine to fail-fast on cheap, selective conditions.
     */
    static class SelectivityProfile {
        private final Int2IntOpenHashMap fieldCounts;
        // private final Map<String, Integer> valueCounts; // Not currently used, but kept for future profiling
        private final int totalRules;

        public SelectivityProfile(List<RuleDefinition> definitions, Dictionary fieldDictionary, Dictionary valueDictionary) {
            this.fieldCounts = new Int2IntOpenHashMap();
            // this.valueCounts = new HashMap<>(); // Disabled for now
            this.totalRules = definitions.size();

            for (RuleDefinition def : definitions) {
                if (def.conditions() == null) continue;
                for (RuleDefinition.Condition cond : def.conditions()) {
                    if (cond.field() == null) continue;
                    int fieldId = fieldDictionary.getId(cond.field().toUpperCase().replace('-', '_'));
                    fieldCounts.addTo(fieldId, 1);
                    // if (cond.value() != null) {
                    //    valueCounts.merge(String.valueOf(cond.value()), 1, Integer::sum);
                    // }
                }
            }
        }

        /**
         * Estimates the selectivity of a predicate.
         * Selectivity is a value from 0.0 (very selective, matches rarely)
         * to 1.0 (not selective, matches often).
         *
         * This estimation is based on how often a field appears in rules,
         * modified by a heuristic for the operator type.
         */
        public float calculateSelectivity(int fieldId, Predicate.Operator operator, Object value) {
            if (operator == null) return 0.5f;

            // Base selectivity on how many rules use this field
            int fieldCount = fieldCounts.getOrDefault(fieldId, 1);
            float baseSelectivity = Math.min(1.0f, (float) fieldCount / totalRules);

            // Adjust selectivity based on operator type (heuristics)
            return switch (operator) {
                case EQUAL_TO, NOT_EQUAL_TO -> baseSelectivity * 0.1f; // Assumed high selectivity
                case GREATER_THAN, LESS_THAN -> baseSelectivity * 0.3f;
                case GREATER_THAN_OR_EQUAL, LESS_THAN_OR_EQUAL -> baseSelectivity * 0.35f;
                case IS_ANY_OF, IS_NONE_OF ->
                        baseSelectivity * (value instanceof List ? ((List<?>) value).size() * 0.15f : 0.2f); // Less selective as list size grows
                case CONTAINS, STARTS_WITH, ENDS_WITH -> baseSelectivity * 0.4f;
                case REGEX -> baseSelectivity * 0.5f; // Assumed low selectivity
                case IS_NULL, IS_NOT_NULL -> baseSelectivity * 0.05f; // Assumed very high selectivity
                default -> 0.5f; // Default for unknown
            };
        }

        /**
         * Gets the relative CPU cost of evaluating an operator.
         * A simple EQUAL_TO is cheap (1.0), while a REGEX is very expensive (10.0).
         */
        public float getCost(Predicate.Operator operator) {
            if (operator == null) return 1.0f;

            return switch (operator) {
                // Cheapest operations
                case EQUAL_TO, NOT_EQUAL_TO, IS_NULL, IS_NOT_NULL -> 1.0f;
                // Simple numeric comparisons
                case GREATER_THAN, LESS_THAN, GREATER_THAN_OR_EQUAL, LESS_THAN_OR_EQUAL -> 1.5f;
                // List operations (cost increases with list size, modeled as 2.0)
                case IS_ANY_OF, IS_NONE_OF -> 2.0f;
                // String substring operations
                case CONTAINS, STARTS_WITH, ENDS_WITH -> 3.0f;
                // Most expensive operation
                case REGEX -> 10.0f;
                // Default cost
                default -> 1.0f;
            };
        }
    }
}