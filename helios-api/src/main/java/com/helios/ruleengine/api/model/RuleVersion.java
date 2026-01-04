/*
 * Copyright (c) 2025 Helios Rule Engine
 * Licensed under the Apache License, Version 2.0
 */
package com.helios.ruleengine.api.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Represents a historical version of a rule.
 * Each time a rule is created, updated, or rolled back, a new version record is created.
 *
 * <p>This record captures the complete state of a rule at a specific point in time,
 * enabling version comparison, history viewing, and rollback operations.
 */
public record RuleVersion(
    @JsonProperty("rule_code") String ruleCode,
    @JsonProperty("version") int version,
    @JsonProperty("description") String description,
    @JsonProperty("conditions") List<RuleDefinition.Condition> conditions,
    @JsonProperty("priority") Integer priority,
    @JsonProperty("enabled") Boolean enabled,
    @JsonProperty("author") String author,
    @JsonProperty("timestamp") Instant timestamp,
    @JsonProperty("change_type") ChangeType changeType,
    @JsonProperty("change_summary") String changeSummary,
    @JsonProperty("tags") Set<String> tags,
    @JsonProperty("labels") Map<String, String> labels
) implements Serializable {

    /**
     * Type of change that created this version.
     */
    public enum ChangeType {
        CREATED,
        UPDATED,
        ROLLBACK
    }

    /**
     * Creates a RuleVersion from RuleMetadata for a new rule creation.
     *
     * @param metadata The rule metadata
     * @param author The author who created the rule
     * @return A new RuleVersion instance
     */
    public static RuleVersion fromMetadataCreation(RuleMetadata metadata, String author) {
        return new RuleVersion(
            metadata.ruleCode(),
            1,
            metadata.description(),
            metadata.conditions(),
            metadata.priority(),
            metadata.enabled(),
            author != null ? author : metadata.createdBy(),
            Instant.now(),
            ChangeType.CREATED,
            "Initial rule creation",
            metadata.tags(),
            metadata.labels()
        );
    }

    /**
     * Creates a RuleVersion from RuleMetadata for an update.
     *
     * @param metadata The updated rule metadata
     * @param version The new version number
     * @param author The author who made the update
     * @param changeSummary Summary of the changes made
     * @return A new RuleVersion instance
     */
    public static RuleVersion fromMetadataUpdate(RuleMetadata metadata, int version, String author, String changeSummary) {
        return new RuleVersion(
            metadata.ruleCode(),
            version,
            metadata.description(),
            metadata.conditions(),
            metadata.priority(),
            metadata.enabled(),
            author != null ? author : metadata.lastModifiedBy(),
            Instant.now(),
            ChangeType.UPDATED,
            changeSummary != null ? changeSummary : "Rule updated",
            metadata.tags(),
            metadata.labels()
        );
    }

    /**
     * Creates a RuleVersion for a rollback operation.
     *
     * @param metadata The restored rule metadata
     * @param version The new version number (after rollback)
     * @param author The author who performed the rollback
     * @param restoredFromVersion The version that was restored
     * @return A new RuleVersion instance
     */
    public static RuleVersion fromRollback(RuleMetadata metadata, int version, String author, int restoredFromVersion) {
        return new RuleVersion(
            metadata.ruleCode(),
            version,
            metadata.description(),
            metadata.conditions(),
            metadata.priority(),
            metadata.enabled(),
            author,
            Instant.now(),
            ChangeType.ROLLBACK,
            "Rolled back to version " + restoredFromVersion,
            metadata.tags(),
            metadata.labels()
        );
    }

    /**
     * Converts this version to a RuleMetadata instance.
     * Useful for restoring a rule to this version's state.
     *
     * @param existingMetadata The current rule metadata (to preserve system fields)
     * @return A RuleMetadata instance with this version's data
     */
    public RuleMetadata toRuleMetadata(RuleMetadata existingMetadata) {
        return new RuleMetadata(
            ruleCode,
            description,
            conditions,
            priority,
            enabled,
            existingMetadata != null ? existingMetadata.createdBy() : author,
            existingMetadata != null ? existingMetadata.createdAt() : timestamp,
            author,
            Instant.now(),
            existingMetadata != null ? existingMetadata.version() + 1 : version + 1,
            tags,
            labels,
            existingMetadata != null ? existingMetadata.combinationIds() : Set.of(),
            existingMetadata != null ? existingMetadata.estimatedSelectivity() : null,
            existingMetadata != null ? existingMetadata.isVectorizable() : null,
            existingMetadata != null ? existingMetadata.compilationStatus() : "PENDING"
        );
    }
}
