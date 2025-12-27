package com.helios.ruleengine.api.model;

import java.util.List;

/**
 * Request to execute rule import
 */
public record ImportExecutionRequest(
    List<String> importIds,              // IDs of rules to import from validation
    List<RuleMetadata> rules,            // The actual rules to import
    ConflictResolution conflictResolution // How to handle conflicts
) {
    public enum ConflictResolution {
        SKIP,      // Skip conflicting rules
        OVERWRITE, // Overwrite existing rules
        RENAME     // Auto-rename imported rules
    }
}
