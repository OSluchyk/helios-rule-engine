package com.helios.ruleengine.api.model;

import java.util.List;

/**
 * Result of batch evaluation containing all match results and aggregated statistics.
 */
public record BatchEvaluationResult(
    List<MatchResult> results,
    BatchStats stats
) {}
