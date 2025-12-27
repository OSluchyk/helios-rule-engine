package com.helios.ruleengine.api.model;

import java.util.List;

/**
 * Response from validating rules for import
 */
public record ImportValidationResponse(
    List<ImportedRuleStatus> rules,
    ValidationStats stats
) {
    public record ValidationStats(
        int total,
        int valid,
        int warnings,
        int errors
    ) {}
}
