/*
 * Copyright (c) 2025 Helios Rule Engine
 * Licensed under the Apache License, Version 2.0
 */
package com.helios.ruleengine.api.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;
import java.util.List;

/**
 * Detailed execution trace for a single event evaluation.
 * Captures step-by-step predicate outcomes, timing breakdowns, and rule evaluation details.
 *
 * <p>This trace is designed for debugging and visualization purposes.
 * It provides complete visibility into the evaluation pipeline, including:
 * <ul>
 *   <li>Stage-by-stage timing breakdown</li>
 *   <li>Predicate-level evaluation outcomes</li>
 *   <li>Rule-level matching details</li>
 *   <li>Cache effectiveness metrics</li>
 * </ul>
 *
 * <h2>Performance Note</h2>
 * Trace capture is <b>disabled by default</b> to avoid overhead on the hot path.
 * When enabled, it introduces ~10% overhead which is acceptable for debugging scenarios.
 *
 * <h2>Usage</h2>
 * <pre>
 * IRuleEvaluator evaluator = new RuleEvaluator(model);
 * EvaluationResult result = evaluator.evaluateWithTrace(event);
 * EvaluationTrace trace = result.trace();
 *
 * // Analyze timing
 * System.out.println("Total: " + trace.totalDurationNanos() + "ns");
 * System.out.println("Predicate eval: " + trace.predicateEvalNanos() + "ns");
 *
 * // Examine predicate outcomes
 * for (PredicateOutcome outcome : trace.predicateOutcomes()) {
 *     if (!outcome.matched()) {
 *         System.out.println("Failed: " + outcome.fieldName() + " " +
 *                          outcome.operator() + " " + outcome.expectedValue());
 *     }
 * }
 * </pre>
 */
public record EvaluationTrace(
    @JsonProperty("event_id") String eventId,
    @JsonProperty("total_duration_nanos") long totalDurationNanos,

    // Stage timings (breakdown of totalDurationNanos)
    @JsonProperty("dict_encoding_nanos") long dictEncodingNanos,
    @JsonProperty("base_condition_nanos") long baseConditionNanos,
    @JsonProperty("predicate_eval_nanos") long predicateEvalNanos,
    @JsonProperty("counter_update_nanos") long counterUpdateNanos,
    @JsonProperty("match_detection_nanos") long matchDetectionNanos,

    // Predicate-level details
    @JsonProperty("predicate_outcomes") List<PredicateOutcome> predicateOutcomes,

    // Rule-level details
    @JsonProperty("rule_details") List<RuleEvaluationDetail> ruleDetails,

    // Cache effectiveness
    @JsonProperty("base_condition_cache_hit") boolean baseConditionCacheHit,
    @JsonProperty("eligible_rules_count") int eligibleRulesCount,

    // Final results
    @JsonProperty("matched_rule_codes") List<String> matchedRuleCodes
) implements Serializable {

    /**
     * Outcome of a single predicate evaluation.
     * Records whether a predicate passed or failed, along with expected vs actual values.
     */
    public record PredicateOutcome(
        @JsonProperty("predicate_id") int predicateId,
        @JsonProperty("field_name") String fieldName,
        @JsonProperty("operator") String operator,
        @JsonProperty("expected_value") Object expectedValue,
        @JsonProperty("actual_value") Object actualValue,
        @JsonProperty("matched") boolean matched,
        @JsonProperty("evaluation_nanos") long evaluationNanos
    ) implements Serializable {

        /**
         * Returns a human-readable description of this outcome.
         */
        public String describe() {
            if (matched) {
                return String.format("✓ %s %s %s (actual: %s)",
                    fieldName, operator, expectedValue, actualValue);
            } else {
                return String.format("✗ %s %s %s (actual: %s)",
                    fieldName, operator, expectedValue, actualValue);
            }
        }
    }

    /**
     * Evaluation details for a single rule combination.
     * Shows how many predicates matched vs required, and which predicates failed.
     */
    public record RuleEvaluationDetail(
        @JsonProperty("combination_id") int combinationId,
        @JsonProperty("rule_code") String ruleCode,
        @JsonProperty("priority") int priority,
        @JsonProperty("predicates_matched") int predicatesMatched,
        @JsonProperty("predicates_required") int predicatesRequired,
        @JsonProperty("final_match") boolean finalMatch,
        @JsonProperty("failed_predicates") List<String> failedPredicates
    ) implements Serializable {

        /**
         * Returns the match percentage (0-100).
         */
        public double matchPercentage() {
            return predicatesRequired == 0 ? 0.0 :
                (double) predicatesMatched / predicatesRequired * 100.0;
        }

        /**
         * Returns a human-readable description of this rule's evaluation.
         */
        public String describe() {
            if (finalMatch) {
                return String.format("✓ %s matched (%d/%d predicates)",
                    ruleCode, predicatesMatched, predicatesRequired);
            } else {
                return String.format("✗ %s failed (%d/%d predicates) - missing: %s",
                    ruleCode, predicatesMatched, predicatesRequired,
                    String.join(", ", failedPredicates));
            }
        }
    }

    /**
     * Returns the total evaluation time in microseconds.
     */
    public double totalDurationMicros() {
        return totalDurationNanos / 1000.0;
    }

    /**
     * Returns the total evaluation time in milliseconds.
     */
    public double totalDurationMillis() {
        return totalDurationNanos / 1_000_000.0;
    }

    /**
     * Returns the percentage of time spent in each stage.
     */
    public StageTimingBreakdown getTimingBreakdown() {
        double total = totalDurationNanos;
        if (total == 0) total = 1; // Avoid division by zero

        return new StageTimingBreakdown(
            dictEncodingNanos / total * 100.0,
            baseConditionNanos / total * 100.0,
            predicateEvalNanos / total * 100.0,
            counterUpdateNanos / total * 100.0,
            matchDetectionNanos / total * 100.0
        );
    }

    /**
     * Timing breakdown as percentages.
     */
    public record StageTimingBreakdown(
        double dictEncodingPercent,
        double baseConditionPercent,
        double predicateEvalPercent,
        double counterUpdatePercent,
        double matchDetectionPercent
    ) {}
}
