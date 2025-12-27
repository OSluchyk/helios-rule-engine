package com.helios.ruleengine.api.model;

import java.util.List;

/**
 * Response from executing rule import
 */
public record ImportExecutionResponse(
    int imported,
    int skipped,
    int failed,
    List<ImportResult> results
) {
    public record ImportResult(
        String ruleCode,
        boolean success,
        String message
    ) {}
}
