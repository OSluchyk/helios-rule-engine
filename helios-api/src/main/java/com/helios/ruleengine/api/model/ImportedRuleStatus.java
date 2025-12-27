package com.helios.ruleengine.api.model;

/**
 * Validation status for an imported rule
 */
public record ImportedRuleStatus(
    String importId,         // Unique ID for this import
    RuleMetadata rule,       // The rule metadata
    Status status,           // valid, warning, error
    java.util.List<String> issues,  // List of validation issues
    Conflict conflict        // Conflict information if any
) {
    public enum Status {
        VALID,
        WARNING,
        ERROR
    }

    public record Conflict(
        ConflictType type,
        String existingRuleCode
    ) {}

    public enum ConflictType {
        DUPLICATE_CODE,
        DUPLICATE_NAME,
        PRIORITY_CONFLICT
    }
}
