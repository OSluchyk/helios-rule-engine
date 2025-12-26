package com.helios.ruleengine.api.model;

/**
 * Aggregated statistics for batch evaluation.
 * Provides performance metrics and match analysis across multiple events.
 */
public record BatchStats(
    int totalEvents,
    long avgEvaluationTimeNanos,
    double matchRate,
    long minEvaluationTimeNanos,
    long maxEvaluationTimeNanos,
    int totalMatchedRules,
    double avgRulesMatchedPerEvent
) {
    /**
     * Creates an empty BatchStats instance for cases with no events.
     */
    public static BatchStats empty() {
        return new BatchStats(0, 0, 0.0, 0, 0, 0, 0.0);
    }
}
