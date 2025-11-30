/*
 * Copyright (c) 2025 Helios Rule Engine
 * Licensed under the Apache License, Version 2.0
 */
package com.helios.ruleengine.api;

import com.helios.ruleengine.api.model.Event;
import com.helios.ruleengine.api.model.MatchResult;

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
}