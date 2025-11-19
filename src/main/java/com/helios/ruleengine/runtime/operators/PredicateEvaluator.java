package com.helios.ruleengine.runtime.operators;

import com.helios.ruleengine.runtime.context.EvaluationContext;
import com.helios.ruleengine.runtime.model.EngineModel;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.IntSet;

import java.util.logging.Logger;

/**
 * L5-LEVEL FACADE: Unified entry point for ALL predicate evaluation.
 *
 * DESIGN PATTERN: Facade + Strategy
 * - Facade: Single interface hiding complexity of multiple evaluators
 * - Strategy: Each operator type has its own optimized strategy
 *
 * RESPONSIBILITIES:
 * - Dispatch to appropriate evaluator based on field type
 * - Coordinate evaluation across different operator types
 * - Aggregate results into evaluation context
 *
 * DOES NOT:
 * - Know implementation details of each operator
 * - Handle vectorization or indexing directly
 * - Manage caching (delegated to BaseConditionEvaluator)
 *
 * PERFORMANCE:
 * - Zero overhead dispatching (JIT inlines everything)
 * - Each specialized evaluator maintains its own optimizations
 * - No allocations in steady state
 *
 * @author Google L5 Engineering Standards
 */
public final class PredicateEvaluator {
    private static final Logger logger = Logger.getLogger(PredicateEvaluator.class.getName());

    private final EngineModel model;

    // Specialized evaluators - each handles one operator family
    private final NumericOperatorEvaluator numericEvaluator;
    private final StringOperatorEvaluator stringEvaluator;
    private final RegexOperatorEvaluator regexEvaluator;
    private final EqualityOperatorEvaluator equalityEvaluator;

    // Performance tracking
    private long totalEvaluations = 0;
    private long numericOps = 0;
    private long stringOps = 0;
    private long regexOps = 0;
    private long equalityOps = 0;

    public PredicateEvaluator(EngineModel model) {
        this.model = model;

        // Initialize specialized evaluators
        this.numericEvaluator = new NumericOperatorEvaluator(model);
        this.stringEvaluator = new StringOperatorEvaluator(model);
        this.regexEvaluator = new RegexOperatorEvaluator(model);
        this.equalityEvaluator = new EqualityOperatorEvaluator(model);

        logger.info("PredicateEvaluator initialized with 4 specialized evaluators");
    }

    /**
     * Evaluate all predicates for a single field.
     *
     * DISPATCH LOGIC:
     * 1. Check if field has any predicates
     * 2. Get field value from event attributes
     * 3. Dispatch to appropriate evaluator(s)
     * 4. Aggregate results into context
     *
     * @param fieldId Field identifier (dictionary-encoded)
     * @param attributes Event attributes (dictionary-encoded)
     * @param ctx Evaluation context (accumulates results)
     * @param eligiblePredicateIds Only evaluate these predicates (null = all)
     */
    public void evaluateField(int fieldId, Int2ObjectMap<Object> attributes,
                              EvaluationContext ctx, IntSet eligiblePredicateIds) {
        totalEvaluations++;

        // Get field value
        Object value = attributes.get(fieldId);
        if (value == null) {
            return; // No value, no evaluation
        }

        // Dispatch based on value type and available operators

        // 1. Numeric operators (BETWEEN, GREATER_THAN, LESS_THAN)
        if (value instanceof Number) {
            numericEvaluator.evaluateNumeric(fieldId, ((Number) value).doubleValue(),
                    ctx, eligiblePredicateIds);
            numericOps++;
        }

        // 2. String operators (CONTAINS, REGEX)
        String stringValue = extractStringValue(value);
        if (stringValue != null) {
            stringEvaluator.evaluateContains(fieldId, stringValue,
                    ctx, eligiblePredicateIds);
            stringOps++;

            regexEvaluator.evaluateRegex(fieldId, stringValue,
                    ctx, eligiblePredicateIds);
            regexOps++;
        }

        // 3. Equality operators (EQUAL_TO, IS_ANY_OF) - always evaluated
        equalityEvaluator.evaluateEquality(fieldId, value,
                ctx, eligiblePredicateIds);
        equalityOps++;
    }

    /**
     * Extract string value from various types.
     * Handles dictionary-encoded integers and raw strings.
     */
    private String extractStringValue(Object value) {
        if (value instanceof String) {
            return (String) value;
        } else if (value instanceof Integer) {
            // Dictionary-encoded string
            return model.getValueDictionary().decode((Integer) value);
        }
        return null;
    }

    /**
     * Get performance metrics for monitoring.
     */
    public PerformanceMetrics getMetrics() {
        return new PerformanceMetrics(
                totalEvaluations,
                numericOps,
                stringOps,
                regexOps,
                equalityOps,
                numericEvaluator.getMetrics(),
                stringEvaluator.getMetrics(),
                regexEvaluator.getMetrics()
        );
    }

    /**
     * Performance metrics snapshot (immutable).
     */
    public record PerformanceMetrics(
            long totalEvaluations,
            long numericOperations,
            long stringOperations,
            long regexOperations,
            long equalityOperations,
            NumericOperatorEvaluator.Metrics numericMetrics,
            StringOperatorEvaluator.Metrics stringMetrics,
            RegexOperatorEvaluator.Metrics regexMetrics
    ) {}
}