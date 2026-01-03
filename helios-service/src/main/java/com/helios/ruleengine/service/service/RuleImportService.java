package com.helios.ruleengine.service.service;

import com.helios.ruleengine.api.model.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for handling rule import operations
 */
@ApplicationScoped
public class RuleImportService {

    private static final Logger logger = LoggerFactory.getLogger(RuleImportService.class);

    @Inject
    RuleManagementService ruleManagementService;

    /**
     * Validate rules for import
     */
    public ImportValidationResponse validateImport(ImportValidationRequest request) {
        logger.info("Validating {} rules for import", request.rules() != null ? request.rules().size() : 0);

        List<ImportedRuleStatus> statuses = new ArrayList<>();
        int valid = 0, warnings = 0, errors = 0;

        // Get existing rules for conflict detection
        List<RuleMetadata> existingRules = ruleManagementService.getAllRules();
        Map<String, RuleMetadata> existingByCode = existingRules.stream()
                .collect(Collectors.toMap(RuleMetadata::ruleCode, r -> r));

        for (RuleMetadata rule : request.rules()) {
            String importId = UUID.randomUUID().toString();
            List<String> issues = new ArrayList<>();
            ImportedRuleStatus.Conflict conflict = null;
            ImportedRuleStatus.Status status;

            // Validate required fields
            if (rule.ruleCode() == null || rule.ruleCode().isBlank()) {
                issues.add("Missing required field: rule_code");
            }
            if (rule.description() == null || rule.description().isBlank()) {
                issues.add("Missing required field: description");
            }
            if (rule.conditions() == null || rule.conditions().isEmpty()) {
                issues.add("Missing required field: conditions");
            }

            // Check for conflicts
            if (rule.ruleCode() != null && existingByCode.containsKey(rule.ruleCode())) {
                conflict = new ImportedRuleStatus.Conflict(
                        ImportedRuleStatus.ConflictType.DUPLICATE_CODE,
                        rule.ruleCode()
                );
            }

            // Validate priority range
            if (rule.priority() < 0 || rule.priority() > 1000) {
                issues.add("Priority must be between 0 and 1000");
            } else if (rule.priority() > 900) {
                issues.add("Priority value (" + rule.priority() + ") is unusually high");
            }

            // Validate operators are supported
            if (rule.conditions() != null) {
                for (var condition : rule.conditions()) {
                    if (!isSupportedOperator(condition.operator())) {
                        issues.add("Unsupported operator: " + condition.operator() +
                                   " (supported: EQUAL_TO, IS_ANY_OF, GREATER_THAN, LESS_THAN, BETWEEN, CONTAINS, REGEX, etc.)");
                    }
                }
            }

            // Check for optimization opportunities
            if (rule.conditions() != null) {
                checkForOptimizationOpportunities(rule.conditions(), issues);
            }

            // Determine overall status
            if (!issues.isEmpty() && issues.stream().anyMatch(i ->
                    i.startsWith("Missing required") ||
                    i.startsWith("Unsupported operator") ||
                    i.contains("Priority must be between"))) {
                status = ImportedRuleStatus.Status.ERROR;
                errors++;
            } else if (!issues.isEmpty() || conflict != null) {
                status = ImportedRuleStatus.Status.WARNING;
                warnings++;
            } else {
                status = ImportedRuleStatus.Status.VALID;
                valid++;
            }

            statuses.add(new ImportedRuleStatus(importId, rule, status, issues, conflict));
        }

        ImportValidationResponse.ValidationStats stats =
                new ImportValidationResponse.ValidationStats(statuses.size(), valid, warnings, errors);

        logger.info("Validation complete: {} valid, {} warnings, {} errors", valid, warnings, errors);

        return new ImportValidationResponse(statuses, stats);
    }

    /**
     * Execute rule import
     */
    public ImportExecutionResponse executeImport(ImportExecutionRequest request) {
        logger.info("Executing import of {} rules with {} conflict resolution",
                request.rules().size(), request.conflictResolution());

        List<ImportExecutionResponse.ImportResult> results = new ArrayList<>();
        int imported = 0, skipped = 0, failed = 0;

        // Get existing rules
        List<RuleMetadata> existingRules = ruleManagementService.getAllRules();
        Set<String> existingCodes = existingRules.stream()
                .map(RuleMetadata::ruleCode)
                .collect(Collectors.toSet());

        for (RuleMetadata rule : request.rules()) {
            try {
                String ruleCode = rule.ruleCode();
                boolean exists = existingCodes.contains(ruleCode);

                // Normalize operators in conditions
                RuleMetadata normalizedRule = normalizeRuleOperators(rule);

                // Enable disabled rules during import if the user chose to do so
                if (request.shouldEnableDisabledRules() && (normalizedRule.enabled() == null || !normalizedRule.enabled())) {
                    normalizedRule = new RuleMetadata(
                            normalizedRule.ruleCode(),
                            normalizedRule.description(),
                            normalizedRule.conditions(),
                            normalizedRule.priority(),
                            true,  // Enable the rule
                            normalizedRule.createdBy(),
                            normalizedRule.createdAt(),
                            normalizedRule.lastModifiedBy(),
                            normalizedRule.lastModifiedAt(),
                            normalizedRule.version(),
                            normalizedRule.tags(),
                            normalizedRule.labels(),
                            normalizedRule.combinationIds(),
                            normalizedRule.estimatedSelectivity(),
                            normalizedRule.isVectorizable(),
                            normalizedRule.compilationStatus()
                    );
                }

                // Handle conflicts based on strategy
                if (exists) {
                    switch (request.conflictResolution()) {
                        case SKIP:
                            results.add(new ImportExecutionResponse.ImportResult(
                                    ruleCode, false, "Skipped - rule already exists"
                            ));
                            skipped++;
                            continue;

                        case RENAME:
                            // Generate new code with suffix
                            String newCode = generateUniqueCode(ruleCode, existingCodes);
                            // Create new rule with renamed code (enabled status already handled above)
                            RuleMetadata renamedRule = new RuleMetadata(
                                    newCode,
                                    normalizedRule.description() + " (imported)",
                                    normalizedRule.conditions(),
                                    normalizedRule.priority(),
                                    normalizedRule.enabled(),  // Use the (possibly modified) enabled status
                                    normalizedRule.createdBy(),
                                    normalizedRule.createdAt(),
                                    normalizedRule.lastModifiedBy(),
                                    normalizedRule.lastModifiedAt(),
                                    normalizedRule.version(),
                                    normalizedRule.tags(),
                                    normalizedRule.labels(),
                                    normalizedRule.combinationIds(),
                                    normalizedRule.estimatedSelectivity(),
                                    normalizedRule.isVectorizable(),
                                    normalizedRule.compilationStatus()
                            );
                            ruleManagementService.createRule(renamedRule);
                            results.add(new ImportExecutionResponse.ImportResult(
                                    newCode, true, "Imported with new code: " + newCode
                            ));
                            imported++;
                            existingCodes.add(newCode);
                            break;

                        case OVERWRITE:
                            // Update existing rule with normalized operators
                            ruleManagementService.updateRule(ruleCode, normalizedRule);
                            results.add(new ImportExecutionResponse.ImportResult(
                                    ruleCode, true, "Overwritten existing rule"
                            ));
                            imported++;
                            break;
                    }
                } else {
                    // New rule - create it with normalized operators
                    ruleManagementService.createRule(normalizedRule);
                    results.add(new ImportExecutionResponse.ImportResult(
                            ruleCode, true, "Imported successfully"
                    ));
                    imported++;
                    existingCodes.add(ruleCode);
                }

            } catch (Exception e) {
                logger.error("Failed to import rule: " + rule.ruleCode(), e);
                results.add(new ImportExecutionResponse.ImportResult(
                        rule.ruleCode(), false, "Failed: " + e.getMessage()
                ));
                failed++;
            }
        }

        logger.info("Import complete: {} imported, {} skipped, {} failed", imported, skipped, failed);

        return new ImportExecutionResponse(imported, skipped, failed, results);
    }

    /**
     * Normalize operator to canonical form, supporting aliases
     */
    private String normalizeOperator(String operator) {
        if (operator == null) return null;

        // Normalize to uppercase and trim
        String normalized = operator.toUpperCase().trim();

        // Map aliases to canonical operators
        return switch (normalized) {
            // EQUAL_TO aliases
            case "EQUAL_TO", "EQUALS", "EQ", "==", "=" -> "EQUAL_TO";
            case "IS_EQUAL_TO", "IS_EQUAL" -> "EQUAL_TO";

            // NOT_EQUAL_TO aliases
            case "NOT_EQUAL_TO", "NOT_EQUALS", "NE", "NEQ", "!=", "<>" -> "NOT_EQUAL_TO";
            case "IS_NOT_EQUAL_TO", "IS_NOT_EQUAL" -> "NOT_EQUAL_TO";

            // GREATER_THAN aliases
            case "GREATER_THAN", "GT", ">", "IS_GREATER_THAN", "GREATER" -> "GREATER_THAN";
            case "GREATER_THAN_OR_EQUAL", "GTE", "GE", ">=", "IS_GREATER_THAN_OR_EQUAL" -> "GREATER_THAN";

            // LESS_THAN aliases
            case "LESS_THAN", "LT", "<", "IS_LESS_THAN", "LESS" -> "LESS_THAN";
            case "LESS_THAN_OR_EQUAL", "LTE", "LE", "<=", "IS_LESS_THAN_OR_EQUAL" -> "LESS_THAN";

            // BETWEEN aliases
            case "BETWEEN", "IN_RANGE", "RANGE" -> "BETWEEN";

            // IS_ANY_OF aliases
            case "IS_ANY_OF", "IN", "ANY_OF", "ONE_OF" -> "IS_ANY_OF";

            // IS_NONE_OF aliases
            case "IS_NONE_OF", "NOT_IN", "NONE_OF" -> "IS_NONE_OF";

            // CONTAINS aliases
            case "CONTAINS", "HAS", "INCLUDES" -> "CONTAINS";

            // STARTS_WITH aliases
            case "STARTS_WITH", "BEGINS_WITH", "PREFIX" -> "STARTS_WITH";

            // ENDS_WITH aliases
            case "ENDS_WITH", "SUFFIX" -> "ENDS_WITH";

            // REGEX aliases
            case "REGEX", "MATCHES", "REGEXP", "PATTERN" -> "REGEX";

            // NULL checks
            case "IS_NULL", "NULL", "ISNULL" -> "IS_NULL";
            case "IS_NOT_NULL", "NOT_NULL", "NOTNULL", "ISNOTNULL" -> "IS_NOT_NULL";

            // If no alias matches, return original normalized value
            default -> normalized;
        };
    }

    /**
     * Check if an operator is supported (after normalization)
     */
    private boolean isSupportedOperator(String operator) {
        if (operator == null) return false;

        // Normalize first
        String normalized = normalizeOperator(operator);

        // Check if normalized operator is supported
        return normalized.equals("EQUAL_TO") ||
               normalized.equals("NOT_EQUAL_TO") ||
               normalized.equals("IS_ANY_OF") ||
               normalized.equals("IS_NONE_OF") ||
               normalized.equals("GREATER_THAN") ||
               normalized.equals("LESS_THAN") ||
               normalized.equals("BETWEEN") ||
               normalized.equals("CONTAINS") ||
               normalized.equals("REGEX") ||
               normalized.equals("IS_NULL") ||
               normalized.equals("IS_NOT_NULL") ||
               normalized.equals("STARTS_WITH") ||
               normalized.equals("ENDS_WITH");
    }

    /**
     * Check if an operator is vectorizable (after normalization)
     */
    private boolean isVectorizable(String operator) {
        if (operator == null) return false;

        String normalized = normalizeOperator(operator);
        return normalized.equals("EQUAL_TO") ||
               normalized.equals("NOT_EQUAL_TO") ||
               normalized.equals("IS_ANY_OF") ||
               normalized.equals("IS_NONE_OF");
    }

    /**
     * Normalize all operators in a rule's conditions
     */
    private RuleMetadata normalizeRuleOperators(RuleMetadata rule) {
        if (rule.conditions() == null || rule.conditions().isEmpty()) {
            return rule;
        }

        // Normalize operators in all conditions
        List<RuleDefinition.Condition> normalizedConditions = rule.conditions().stream()
                .map(condition -> new RuleDefinition.Condition(
                        condition.field(),
                        normalizeOperator(condition.operator()),
                        condition.value()
                ))
                .toList();

        // Return new rule with normalized conditions
        return new RuleMetadata(
                rule.ruleCode(),
                rule.description(),
                normalizedConditions,
                rule.priority(),
                rule.enabled(),
                rule.createdBy(),
                rule.createdAt(),
                rule.lastModifiedBy(),
                rule.lastModifiedAt(),
                rule.version(),
                rule.tags(),
                rule.labels(),
                rule.combinationIds(),
                rule.estimatedSelectivity(),
                rule.isVectorizable(),
                rule.compilationStatus()
        );
    }

    /**
     * Check for actionable optimization opportunities in rule conditions.
     * Only warns when there's a realistic alternative that would improve performance.
     */
    private void checkForOptimizationOpportunities(List<RuleDefinition.Condition> conditions, List<String> issues) {
        // Count vectorizable vs non-vectorizable conditions
        long vectorizableCount = conditions.stream()
                .filter(c -> isVectorizable(c.operator()))
                .count();

        long nonVectorizableCount = conditions.size() - vectorizableCount;

        // Only warn in specific actionable cases
        for (var condition : conditions) {
            String operator = normalizeOperator(condition.operator());

            // Case 1: Using EQUAL_TO with null value - suggest IS_NULL instead
            if (operator.equals("EQUAL_TO") && condition.value() == null) {
                issues.add("Consider using IS_NULL instead of EQUAL_TO for null checks (field: " + condition.field() + ")");
            }

            // Case 2: Using NOT_EQUAL_TO with null - suggest IS_NOT_NULL
            if (operator.equals("NOT_EQUAL_TO") && condition.value() == null) {
                issues.add("Consider using IS_NOT_NULL instead of NOT_EQUAL_TO for null checks (field: " + condition.field() + ")");
            }

            // Case 3: EQUAL_TO used multiple times on same field - suggest IS_ANY_OF
            long sameFieldEqualCount = conditions.stream()
                    .filter(c -> c.field().equals(condition.field()))
                    .filter(c -> normalizeOperator(c.operator()).equals("EQUAL_TO"))
                    .count();

            if (sameFieldEqualCount > 1 && operator.equals("EQUAL_TO")) {
                // Only add this warning once per field
                String warningMsg = "Multiple EQUAL_TO conditions on field '" + condition.field() +
                                  "' - consider combining into IS_ANY_OF for better performance";
                if (!issues.contains(warningMsg)) {
                    issues.add(warningMsg);
                }
            }
        }

        // Note: We deliberately DO NOT warn about:
        // - Rules using only GREATER_THAN/LESS_THAN (legitimate comparison logic)
        // - Rules using CONTAINS/REGEX (necessary for pattern matching)
        // - Rules using BETWEEN (efficient range checks)
        // These are valid use cases with no actionable alternative
    }

    /**
     * Generate a unique rule code by appending a suffix
     */
    private String generateUniqueCode(String baseCode, Set<String> existingCodes) {
        int suffix = 1;
        String newCode;
        do {
            newCode = baseCode + "_imported_" + suffix;
            suffix++;
        } while (existingCodes.contains(newCode));
        return newCode;
    }
}
