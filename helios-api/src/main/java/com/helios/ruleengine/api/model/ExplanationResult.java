/*
 * Copyright (c) 2025 Helios Rule Engine
 * Licensed under the Apache License, Version 2.0
 */
package com.helios.ruleengine.api.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;
import java.util.List;

/**
 * Explanation for why a specific rule matched or didn't match an event.
 * Provides human-readable reasoning with condition-by-condition analysis.
 *
 * <p>This DTO is designed to answer the question: "Why did this rule (not) match?"
 * It breaks down each condition and explains whether it passed or failed.
 *
 * <h2>Usage</h2>
 * <pre>
 * IRuleEvaluator evaluator = new RuleEvaluator(model);
 * ExplanationResult explanation = evaluator.explainRule(event, "RULE_12453");
 *
 * if (explanation.matched()) {
 *     System.out.println("Rule matched: " + explanation.summary());
 * } else {
 *     System.out.println("Rule didn't match: " + explanation.summary());
 *     for (ConditionExplanation cond : explanation.conditionExplanations()) {
 *         if (!cond.passed()) {
 *             System.out.println("Failed: " + cond.reason());
 *         }
 *     }
 * }
 * </pre>
 */
public record ExplanationResult(
    @JsonProperty("rule_code") String ruleCode,
    @JsonProperty("matched") boolean matched,
    @JsonProperty("summary") String summary,
    @JsonProperty("condition_explanations") List<ConditionExplanation> conditionExplanations
) implements Serializable {

    /**
     * Explanation for a single condition within a rule.
     * Shows expected vs actual values and whether the condition passed.
     */
    public record ConditionExplanation(
        @JsonProperty("field_name") String fieldName,
        @JsonProperty("operator") String operator,
        @JsonProperty("expected_value") Object expectedValue,
        @JsonProperty("actual_value") Object actualValue,
        @JsonProperty("passed") boolean passed,
        @JsonProperty("reason") String reason
    ) implements Serializable {

        /**
         * Common reasons for condition failure.
         */
        public static final String REASON_VALUE_MISMATCH = "Value mismatch";
        public static final String REASON_FIELD_MISSING = "Field missing from event";
        public static final String REASON_TYPE_MISMATCH = "Type mismatch";
        public static final String REASON_OUT_OF_RANGE = "Value out of range";
        public static final String REASON_NULL_VALUE = "Null value";

        /**
         * Returns a human-readable description of this condition.
         */
        public String describe() {
            if (passed) {
                return String.format("✓ %s %s %s (got: %s)",
                    fieldName, operator, expectedValue, actualValue);
            } else {
                return String.format("✗ %s %s %s (got: %s) - %s",
                    fieldName, operator, expectedValue, actualValue, reason);
            }
        }
    }

    /**
     * Returns the number of conditions that passed.
     */
    public long passedCount() {
        return conditionExplanations.stream()
            .filter(ConditionExplanation::passed)
            .count();
    }

    /**
     * Returns the number of conditions that failed.
     */
    public long failedCount() {
        return conditionExplanations.stream()
            .filter(c -> !c.passed())
            .count();
    }

    /**
     * Returns the total number of conditions.
     */
    public int totalConditions() {
        return conditionExplanations.size();
    }

    /**
     * Returns a detailed text explanation.
     */
    public String toDetailedString() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Rule: %s\n", ruleCode));
        sb.append(String.format("Result: %s\n", matched ? "MATCHED" : "NOT MATCHED"));
        sb.append(String.format("Summary: %s\n\n", summary));
        sb.append(String.format("Conditions (%d passed, %d failed):\n",
            passedCount(), failedCount()));

        for (ConditionExplanation cond : conditionExplanations) {
            sb.append("  ").append(cond.describe()).append("\n");
        }

        return sb.toString();
    }
}
