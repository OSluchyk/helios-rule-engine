/*
 * Copyright (c) 2025 Helios Rule Engine
 * Licensed under the Apache License, Version 2.0
 */
package com.helios.ruleengine.service.service;

import com.helios.ruleengine.api.IRuleCompiler;
import com.helios.ruleengine.api.model.RuleMetadata;
import com.helios.ruleengine.infra.management.EngineModelManager;
import com.helios.ruleengine.service.repository.RuleRepository;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
 * </ul>
 */
@ApplicationScoped
public class RuleManagementService {

    @Inject
    RuleRepository ruleRepository;

    @Inject
    EngineModelManager modelManager;

    @Inject
    IRuleCompiler compiler;

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
            if (ruleRepository.exists(rule.ruleCode())) {
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
            String ruleCode = ruleRepository.save(rule);

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
            if (!ruleRepository.exists(ruleCode)) {
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
            ruleRepository.save(rule);

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

            boolean deleted = ruleRepository.delete(ruleCode);

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
        return ruleRepository.findByCode(ruleCode);
    }

    /**
     * Get all rules.
     *
     * @return list of all rules
     */
    public List<RuleMetadata> getAllRules() {
        return ruleRepository.findAll();
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

    /**
     * Schedule asynchronous recompilation.
     * Uses a single-threaded executor to ensure only one recompilation runs at a time.
     */
    private void scheduleRecompilation() {
        CompletableFuture.runAsync(() -> {
            try {
                Span span = tracer.spanBuilder("async-recompilation").startSpan();
                try (Scope scope = span.makeCurrent()) {
                    // Write current rules to temporary file
                    Path tempFile = writeTempRulesFile();

                    // Recompile from file
                    var newModel = compiler.compile(tempFile);

                    // Hot-swap the model
                    modelManager.updateModel(newModel);

                    span.addEvent("Recompilation successful");

                } catch (Exception e) {
                    span.recordException(e);
                    System.err.println("Recompilation failed: " + e.getMessage());
                    e.printStackTrace();
                } finally {
                    span.end();
                }
            } catch (Exception e) {
                System.err.println("Recompilation scheduling failed: " + e.getMessage());
                e.printStackTrace();
            }
        }, recompilationExecutor);
    }

    /**
     * Write current rules to a temporary JSON file for compilation.
     *
     * @return path to the temporary file
     * @throws IOException if file writing fails
     */
    private Path writeTempRulesFile() throws IOException {
        List<RuleMetadata> rules = ruleRepository.findAll();

        // Convert to JSON
        String json = convertRulesToJson(rules);

        // Write to temp file
        Path tempFile = Files.createTempFile("helios-rules-", ".json");
        Files.writeString(tempFile, json);

        return tempFile;
    }

    /**
     * Convert rules to JSON format.
     * Simple implementation - in production, use Jackson or similar.
     */
    private String convertRulesToJson(List<RuleMetadata> rules) {
        StringBuilder sb = new StringBuilder();
        sb.append("[\n");

        for (int i = 0; i < rules.size(); i++) {
            RuleMetadata rule = rules.get(i);

            sb.append("  {\n");
            sb.append("    \"rule_code\": \"").append(rule.ruleCode()).append("\",\n");
            sb.append("    \"description\": \"").append(rule.description() != null ? rule.description() : "").append("\",\n");
            sb.append("    \"priority\": ").append(rule.priority()).append(",\n");
            sb.append("    \"enabled\": ").append(rule.enabled()).append(",\n");
            sb.append("    \"conditions\": [\n");

            for (int j = 0; j < rule.conditions().size(); j++) {
                var condition = rule.conditions().get(j);

                sb.append("      {\n");
                sb.append("        \"field\": \"").append(condition.field()).append("\",\n");
                sb.append("        \"operator\": \"").append(condition.operator()).append("\",\n");

                // Handle value based on type
                Object value = condition.value();
                if (value instanceof String) {
                    sb.append("        \"value\": \"").append(value).append("\"\n");
                } else {
                    sb.append("        \"value\": ").append(value).append("\n");
                }

                sb.append("      }");
                if (j < rule.conditions().size() - 1) {
                    sb.append(",");
                }
                sb.append("\n");
            }

            sb.append("    ]\n");
            sb.append("  }");
            if (i < rules.size() - 1) {
                sb.append(",");
            }
            sb.append("\n");
        }

        sb.append("]\n");
        return sb.toString();
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
