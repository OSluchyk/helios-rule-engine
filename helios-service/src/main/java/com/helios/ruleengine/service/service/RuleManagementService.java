/*
 * Copyright (c) 2025 Helios Rule Engine
 * Licensed under the Apache License, Version 2.0
 */
package com.helios.ruleengine.service.service;

import com.helios.ruleengine.api.model.RuleDefinition;
import com.helios.ruleengine.api.model.RuleMetadata;
import com.helios.ruleengine.api.model.RuleVersion;
import com.helios.ruleengine.infra.management.EngineModelManager;
import com.helios.ruleengine.service.repository.JdbcRuleRepository;
import com.helios.ruleengine.service.repository.RuleRepository;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Service for managing rules (CRUD operations).
 *
 * <p>This service handles:
 * <ul>
 *   <li>Rule validation</li>
 *   <li>Rule persistence</li>
 *   <li>Triggering recompilation on changes</li>
 *   <li>Hot-reload coordination</li>
 *   <li>Version history (unified in rules table)</li>
 * </ul>
 */
@ApplicationScoped
public class RuleManagementService {

    @Inject
    JdbcRuleRepository jdbcRuleRepository;

    // Use JdbcRuleRepository directly for all operations to ensure version history is stored
    private RuleRepository ruleRepository() {
        return jdbcRuleRepository;
    }

    @Inject
    EngineModelManager modelManager;

    @Inject
    Tracer tracer;

    private final ExecutorService recompilationExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "rule-recompilation");
        t.setDaemon(true);
        return t;
    });

    /**
     * Create a new rule.
     *
     * @param rule the rule metadata
     * @return validation result with rule code if successful
     */
    public RuleValidationResult createRule(RuleMetadata rule) {
        Span span = tracer.spanBuilder("create-rule").startSpan();
        try (Scope scope = span.makeCurrent()) {
            span.setAttribute("ruleCode", rule.ruleCode());

            // Check if rule already exists
            if (ruleRepository().exists(rule.ruleCode())) {
                return new RuleValidationResult(
                    false,
                    null,
                    List.of("Rule with code '" + rule.ruleCode() + "' already exists. Use UPDATE instead.")
                );
            }

            // Validate rule
            var validationResult = validateRule(rule);
            if (!validationResult.isValid()) {
                return validationResult;
            }

            // Save rule
            String ruleCode = ruleRepository().save(rule);

            // Trigger async recompilation
            scheduleRecompilation();

            return new RuleValidationResult(true, ruleCode, List.of());

        } finally {
            span.end();
        }
    }

    /**
     * Update an existing rule.
     *
     * @param ruleCode the rule code
     * @param rule the updated rule metadata
     * @return validation result
     */
    public RuleValidationResult updateRule(String ruleCode, RuleMetadata rule) {
        Span span = tracer.spanBuilder("update-rule").startSpan();
        try (Scope scope = span.makeCurrent()) {
            span.setAttribute("ruleCode", ruleCode);

            // Check if rule exists
            if (!ruleRepository().exists(ruleCode)) {
                return new RuleValidationResult(
                    false,
                    null,
                    List.of("Rule with code '" + ruleCode + "' not found. Use CREATE instead.")
                );
            }

            // Ensure rule code matches
            if (!rule.ruleCode().equals(ruleCode)) {
                return new RuleValidationResult(
                    false,
                    null,
                    List.of("Rule code in payload ('" + rule.ruleCode() + "') does not match URL parameter ('" + ruleCode + "')")
                );
            }

            // Validate rule
            var validationResult = validateRule(rule);
            if (!validationResult.isValid()) {
                return validationResult;
            }

            // Save rule
            ruleRepository().save(rule);

            // Trigger async recompilation
            scheduleRecompilation();

            return new RuleValidationResult(true, ruleCode, List.of());

        } finally {
            span.end();
        }
    }

    /**
     * Delete a rule.
     *
     * @param ruleCode the rule code
     * @return true if deleted, false if not found
     */
    public boolean deleteRule(String ruleCode) {
        Span span = tracer.spanBuilder("delete-rule").startSpan();
        try (Scope scope = span.makeCurrent()) {
            span.setAttribute("ruleCode", ruleCode);

            boolean deleted = ruleRepository().delete(ruleCode);

            if (deleted) {
                // Trigger async recompilation
                scheduleRecompilation();
            }

            return deleted;

        } finally {
            span.end();
        }
    }

    /**
     * Get a rule by code.
     *
     * @param ruleCode the rule code
     * @return the rule metadata, or empty if not found
     */
    public Optional<RuleMetadata> getRule(String ruleCode) {
        return ruleRepository().findByCode(ruleCode);
    }

    /**
     * Get all rules.
     *
     * @return list of all rules
     */
    public List<RuleMetadata> getAllRules() {
        return ruleRepository().findAll();
    }

    /**
     * Validate a rule without saving it.
     *
     * @param rule the rule to validate
     * @return validation result
     */
    public RuleValidationResult validateRule(RuleMetadata rule) {
        // Basic validation
        if (rule.ruleCode() == null || rule.ruleCode().isBlank()) {
            return new RuleValidationResult(false, null, List.of("Rule code is required"));
        }

        if (rule.conditions() == null || rule.conditions().isEmpty()) {
            return new RuleValidationResult(false, null, List.of("At least one condition is required"));
        }

        // Validate each condition
        for (int i = 0; i < rule.conditions().size(); i++) {
            var condition = rule.conditions().get(i);

            if (condition.field() == null || condition.field().isBlank()) {
                return new RuleValidationResult(
                    false,
                    null,
                    List.of("Condition #" + (i + 1) + ": field is required")
                );
            }

            if (condition.operator() == null) {
                return new RuleValidationResult(
                    false,
                    null,
                    List.of("Condition #" + (i + 1) + ": operator is required")
                );
            }

            if (condition.value() == null) {
                return new RuleValidationResult(
                    false,
                    null,
                    List.of("Condition #" + (i + 1) + ": value is required")
                );
            }
        }

        // Advanced validation: try compiling just this rule
        // This would catch semantic errors (invalid operators, type mismatches, etc.)
        // For now, we skip this to avoid the complexity of compiling a single rule

        return new RuleValidationResult(true, rule.ruleCode(), List.of());
    }

    // ========================================
    // Version History Operations
    // ========================================

    /**
     * Get all versions for a specific rule.
     * Uses the unified rules table where all versions are stored.
     *
     * @param ruleCode the rule code
     * @return list of all versions, ordered by version number descending
     */
    public List<RuleVersion> getRuleVersions(String ruleCode) {
        return jdbcRuleRepository.getVersions(ruleCode);
    }

    /**
     * Get a specific version of a rule.
     *
     * @param ruleCode the rule code
     * @param version the version number
     * @return the rule version, or empty if not found
     */
    public Optional<RuleVersion> getRuleVersion(String ruleCode, int version) {
        return jdbcRuleRepository.getVersion(ruleCode, version);
    }

    /**
     * Get the count of versions for a rule.
     *
     * @param ruleCode the rule code
     * @return version count
     */
    public int getRuleVersionCount(String ruleCode) {
        return jdbcRuleRepository.getVersionCount(ruleCode);
    }

    /**
     * Rollback a rule to a specific version.
     * Creates a new version with the state from the specified version.
     *
     * @param ruleCode the rule code
     * @param targetVersion the version to rollback to
     * @param author the user performing the rollback
     * @return validation result
     */
    public RuleValidationResult rollbackRule(String ruleCode, int targetVersion, String author) {
        Span span = tracer.spanBuilder("rollback-rule").startSpan();
        try (Scope scope = span.makeCurrent()) {
            span.setAttribute("ruleCode", ruleCode);
            span.setAttribute("targetVersion", targetVersion);

            // Check if rule exists
            Optional<RuleMetadata> currentRule = ruleRepository().findByCode(ruleCode);
            if (currentRule.isEmpty()) {
                return new RuleValidationResult(
                    false,
                    null,
                    List.of("Rule with code '" + ruleCode + "' not found.")
                );
            }

            // Check if target version exists
            Optional<RuleVersion> targetVersionData = jdbcRuleRepository.getVersion(ruleCode, targetVersion);
            if (targetVersionData.isEmpty()) {
                return new RuleValidationResult(
                    false,
                    null,
                    List.of("Version " + targetVersion + " not found for rule '" + ruleCode + "'.")
                );
            }

            // Cannot rollback to current version
            if (targetVersion == currentRule.get().version()) {
                return new RuleValidationResult(
                    false,
                    null,
                    List.of("Cannot rollback to the current version.")
                );
            }

            // Create new rule metadata from the target version
            RuleVersion versionData = targetVersionData.get();
            RuleMetadata restoredRule = new RuleMetadata(
                ruleCode,
                versionData.description(),
                versionData.conditions(),
                versionData.priority(),
                versionData.enabled(),
                currentRule.get().createdBy(),
                currentRule.get().createdAt(),
                author,
                null, // lastModifiedAt will be set by repository
                null, // version will be incremented by repository
                versionData.tags(),
                versionData.labels(),
                currentRule.get().combinationIds(),
                currentRule.get().estimatedSelectivity(),
                currentRule.get().isVectorizable(),
                "PENDING" // Mark for recompilation
            );

            // Save as rollback
            jdbcRuleRepository.saveAsRollback(restoredRule, targetVersion);

            // Trigger async recompilation
            scheduleRecompilation();

            return new RuleValidationResult(true, ruleCode, List.of());

        } finally {
            span.end();
        }
    }

    /**
     * Schedule asynchronous recompilation from the database.
     * Uses a single-threaded executor to ensure only one recompilation runs at a time.
     */
    private void scheduleRecompilation() {
        CompletableFuture.runAsync(() -> {
            Span span = tracer.spanBuilder("async-recompilation").startSpan();
            try (Scope scope = span.makeCurrent()) {
                List<RuleMetadata> allRules = ruleRepository().findAll();
                List<RuleDefinition> definitions = allRules.stream()
                        .filter(r -> r.enabled() != null && r.enabled())
                        .map(r -> new RuleDefinition(
                                r.ruleCode(), r.conditions(),
                                r.priority() != null ? r.priority() : 0,
                                r.description(),
                                r.enabled() != null ? r.enabled() : true))
                        .toList();

                modelManager.compileFromRules(definitions, null);
                span.addEvent("Recompilation successful");
                span.setAttribute("ruleCount", definitions.size());
            } catch (Exception e) {
                span.recordException(e);
                System.err.println("Recompilation failed: " + e.getMessage());
                e.printStackTrace();
            } finally {
                span.end();
            }
        }, recompilationExecutor);
    }

    /**
     * Result of rule validation.
     */
    public record RuleValidationResult(
        boolean isValid,
        String ruleCode,
        List<String> errors
    ) {}
}
