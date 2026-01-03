package com.helios.ruleengine.api.model;

import java.util.List;

/**
 * Request to execute rule import
 */
public record ImportExecutionRequest(
    List<String> importIds,              // IDs of rules to import from validation
    List<RuleMetadata> rules,            // The actual rules to import
    ConflictResolution conflictResolution, // How to handle conflicts
    Boolean enableDisabledRules          // Whether to enable rules that have enabled=false (default: true)
) {
    /**
     * Constructor with default for enableDisabledRules (for backwards compatibility)
     */
    public ImportExecutionRequest(List<String> importIds, List<RuleMetadata> rules, ConflictResolution conflictResolution) {
        this(importIds, rules, conflictResolution, true);
    }

    /**
     * Returns whether disabled rules should be enabled during import.
     * Defaults to true if not specified.
     */
    public boolean shouldEnableDisabledRules() {
        return enableDisabledRules == null || enableDisabledRules;
    }
    public enum ConflictResolution {
        SKIP,      // Skip conflicting rules
        OVERWRITE, // Overwrite existing rules
        RENAME     // Auto-rename imported rules
    }
}
