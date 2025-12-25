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
 * Enhanced metadata for a logical rule, extending beyond RuleDefinition.
 * Stores authoring, versioning, lifecycle information, and compilation-derived metadata.
 *
 * <p>This DTO is designed for UI integration and management operations.
 * It provides a complete view of a rule including its lifecycle, compilation status,
 * and optimization characteristics.
 *
 * <h2>Usage</h2>
 * <pre>
 * RuleMetadata metadata = new RuleMetadata(
 *     "RULE_12453",
 *     "High-value customer upsell",
 *     conditions,
 *     100,
 *     true,
 *     "user@company.com",
 *     Instant.now(),
 *     null, null, null,
 *     Set.of("upsell", "premium"),
 *     Map.of("team", "marketing"),
 *     null, null, null, null
 * );
 * </pre>
 */
public record RuleMetadata(
    // Core rule identity (from RuleDefinition)
    @JsonProperty("rule_code") String ruleCode,
    @JsonProperty("description") String description,
    @JsonProperty("conditions") List<RuleDefinition.Condition> conditions,
    @JsonProperty("priority") Integer priority,
    @JsonProperty("enabled") Boolean enabled,

    // Authoring and versioning (NEW)
    @JsonProperty("created_by") String createdBy,
    @JsonProperty("created_at") Instant createdAt,
    @JsonProperty("last_modified_by") String lastModifiedBy,
    @JsonProperty("last_modified_at") Instant lastModifiedAt,
    @JsonProperty("version") Integer version,

    // Categorization (NEW)
    @JsonProperty("tags") Set<String> tags,
    @JsonProperty("labels") Map<String, String> labels,

    // Compilation-derived metadata (NEW)
    @JsonProperty("combination_ids") Set<Integer> combinationIds,
    @JsonProperty("estimated_selectivity") Integer estimatedSelectivity,
    @JsonProperty("is_vectorizable") Boolean isVectorizable,
    @JsonProperty("compilation_status") String compilationStatus
) implements Serializable {

    /**
     * Creates a RuleMetadata instance with default values for optional fields.
     */
    public RuleMetadata {
        // Apply defaults for nullable fields
        if (enabled == null) enabled = true;
        if (priority == null) priority = 0;
        if (tags == null) tags = Set.of();
        if (labels == null) labels = Map.of();
        if (createdAt == null) createdAt = Instant.now();
        if (lastModifiedAt == null) lastModifiedAt = createdAt;
        if (version == null) version = 1;
        if (compilationStatus == null) compilationStatus = "OK";
        if (combinationIds == null) combinationIds = Set.of();
    }

    /**
     * Creates a RuleMetadata from a RuleDefinition with minimal metadata.
     *
     * @param definition The rule definition
     * @return A RuleMetadata instance with default values
     */
    public static RuleMetadata fromDefinition(RuleDefinition definition) {
        return new RuleMetadata(
            definition.ruleCode(),
            definition.description(),
            definition.conditions(),
            definition.priority(),
            definition.enabled(),
            null, // createdBy
            null, // createdAt (will default to now)
            null, // lastModifiedBy
            null, // lastModifiedAt
            null, // version (will default to 1)
            null, // tags
            null, // labels
            null, // combinationIds
            null, // estimatedSelectivity
            null, // isVectorizable
            null  // compilationStatus
        );
    }

    /**
     * Creates a copy with updated compilation-derived fields.
     *
     * @param combinationIds Set of physical combination IDs
     * @param estimatedSelectivity Estimated selectivity percentage (0-100)
     * @param isVectorizable Whether all conditions are vectorizable
     * @param compilationStatus Compilation status ("OK", "WARNING", "ERROR")
     * @return A new RuleMetadata instance with updated fields
     */
    public RuleMetadata withCompilationMetadata(
        Set<Integer> combinationIds,
        Integer estimatedSelectivity,
        Boolean isVectorizable,
        String compilationStatus
    ) {
        return new RuleMetadata(
            ruleCode, description, conditions, priority, enabled,
            createdBy, createdAt, lastModifiedBy, lastModifiedAt, version,
            tags, labels,
            combinationIds, estimatedSelectivity, isVectorizable, compilationStatus
        );
    }

    /**
     * Creates a copy with updated versioning metadata.
     *
     * @param modifiedBy User who made the modification
     * @param newVersion New version number
     * @return A new RuleMetadata instance with updated versioning
     */
    public RuleMetadata withVersionUpdate(String modifiedBy, Integer newVersion) {
        return new RuleMetadata(
            ruleCode, description, conditions, priority, enabled,
            createdBy, createdAt, modifiedBy, Instant.now(), newVersion,
            tags, labels,
            combinationIds, estimatedSelectivity, isVectorizable, compilationStatus
        );
    }
}
