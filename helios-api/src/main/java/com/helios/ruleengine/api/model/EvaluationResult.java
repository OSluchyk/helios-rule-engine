/*
 * Copyright (c) 2025 Helios Rule Engine
 * Licensed under the Apache License, Version 2.0
 */
package com.helios.ruleengine.api.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;

/**
 * Enhanced evaluation result containing both match result and optional trace.
 * Used when evaluating with detailed tracing enabled.
 *
 * <h2>Usage</h2>
 * <pre>
 * IRuleEvaluator evaluator = new RuleEvaluator(model);
 * EvaluationResult result = evaluator.evaluateWithTrace(event);
 *
 * // Access standard match result
 * MatchResult matchResult = result.matchResult();
 *
 * // Access detailed trace
 * EvaluationTrace trace = result.trace();
 * System.out.println("Total time: " + trace.totalDurationMillis() + "ms");
 * </pre>
 */
public record EvaluationResult(
    @JsonProperty("match_result") MatchResult matchResult,
    @JsonProperty("trace") EvaluationTrace trace
) implements Serializable {

    /**
     * Returns true if the event matched any rules.
     */
    public boolean hasMatches() {
        return matchResult != null && !matchResult.matchedRules().isEmpty();
    }

    /**
     * Returns the number of matched rules.
     */
    public int matchCount() {
        return matchResult != null ? matchResult.matchedRules().size() : 0;
    }
}
