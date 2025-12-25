/*
 * Copyright (c) 2025 Helios Rule Engine
 * Licensed under the Apache License, Version 2.0
 */
package com.helios.ruleengine.api;

import com.helios.ruleengine.api.model.Event;
import com.helios.ruleengine.api.model.EvaluationResult;
import com.helios.ruleengine.api.model.ExplanationResult;
import com.helios.ruleengine.api.model.MatchResult;

import java.util.List;

/**
 * Contract for evaluating events against compiled rules.
 *
 * <p>This is the primary interface for rule engine consumers.
 * Implementations are thread-safe and can handle concurrent evaluations.
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * IRuleEvaluator evaluator = // obtain from factory or DI
 *
 * Event event = new Event("txn-123", "TRANSACTION", Map.of(
 *     "amount", 15000,
 *     "country", "US"
 * ));
 *
 * MatchResult result = evaluator.evaluate(event);
 *
 * if (result.hasMatches()) {
 *     for (MatchResult.MatchedRule rule : result.matchedRules()) {
 *         System.out.println("Matched: " + rule.ruleCode());
 *     }
 * }
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 * <p>Implementations must be thread-safe. The same evaluator instance
 * can be used concurrently from multiple threads.
 *
 * <h2>Performance Characteristics</h2>
 * <ul>
 *   <li>Target latency: P50 &lt; 150µs, P99 &lt; 800µs at 100K rules</li>
 *   <li>Throughput: 15-20M events/minute</li>
 *   <li>Memory: Zero allocations in hot path (object pooling)</li>
 * </ul>
 */
public interface IRuleEvaluator {

    /**
     * Evaluates an event against all compiled rules.
     *
     * <p>This is the primary evaluation method. It:
     * <ol>
     *   <li>Normalizes event attributes (uppercasing, dictionary encoding)</li>
     *   <li>Evaluates predicates using cached base conditions where possible</li>
     *   <li>Uses counter-based matching to find all matching rules</li>
     *   <li>Applies selection strategy to filter results</li>
     * </ol>
     *
     * @param event the event to evaluate (must not be null)
     * @return match result containing matched rules and metrics
     * @throws NullPointerException if event is null
     */
    MatchResult evaluate(Event event);

    /**
     * Evaluates an event with detailed execution tracing.
     *
     * <p><b>Performance Note:</b> Tracing adds ~10% overhead and is intended
     * for debugging and development only. For production use, call {@link #evaluate(Event)}.
     *
     * @param event the event to evaluate (must not be null)
     * @return evaluation result with match result and detailed trace
     * @throws NullPointerException if event is null
     */
    default EvaluationResult evaluateWithTrace(Event event) {
        // Default implementation: evaluate without trace
        MatchResult result = evaluate(event);
        return new EvaluationResult(result, null);
    }

    /**
     * Explains why a specific rule matched or didn't match an event.
     *
     * <p>This method evaluates the event and provides detailed reasoning
     * about a specific rule's outcome. Useful for debugging and understanding
     * rule behavior.
     *
     * @param event the event to evaluate
     * @param ruleCode the rule to explain
     * @return explanation with condition-by-condition analysis
     * @throws NullPointerException if event or ruleCode is null
     */
    default ExplanationResult explainRule(Event event, String ruleCode) {
        throw new UnsupportedOperationException("explainRule not implemented");
    }

    /**
     * Evaluates multiple events in batch.
     *
     * <p>This method is optimized for test suite execution and bulk processing.
     * It may provide better performance than calling {@link #evaluate(Event)}
     * repeatedly due to reduced context switching and better CPU cache utilization.
     *
     * @param events list of events to evaluate
     * @return list of match results in the same order as input events
     * @throws NullPointerException if events list is null
     */
    default List<MatchResult> evaluateBatch(List<Event> events) {
        // Default implementation: evaluate each event individually
        return events.stream()
            .map(this::evaluate)
            .toList();
    }
}