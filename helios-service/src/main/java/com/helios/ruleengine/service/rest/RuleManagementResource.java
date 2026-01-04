/*
 * Copyright (c) 2025 Helios Rule Engine
 * Licensed under the Apache License, Version 2.0
 */
package com.helios.ruleengine.service.rest;

import com.helios.ruleengine.api.model.RuleMetadata;
import com.helios.ruleengine.api.model.RuleVersion;
import com.helios.ruleengine.infra.management.EngineModelManager;
import com.helios.ruleengine.runtime.model.EngineModel;
import com.helios.ruleengine.service.service.RuleManagementService;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * JAX-RS resource for rule management and metadata endpoints.
 * Provides CRUD operations for rules plus metadata access.
 */
@Path("/rules")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class RuleManagementResource {

    @Inject
    EngineModelManager modelManager;

    @Inject
    RuleManagementService ruleManagementService;

    @Inject
    Tracer tracer;

    // ========================================
    // CRUD Operations
    // ========================================

    /**
     * Create a new rule.
     *
     * @param rule the rule metadata to create
     * @return created rule response
     */
    @POST
    public Response createRule(RuleMetadata rule) {
        Span span = tracer.spanBuilder("http-create-rule").startSpan();
        try (Scope scope = span.makeCurrent()) {
            span.setAttribute("ruleCode", rule.ruleCode());

            var result = ruleManagementService.createRule(rule);

            if (!result.isValid()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of(
                                "error", "Validation failed",
                                "errors", result.errors()
                        ))
                        .build();
            }

            return Response.status(Response.Status.CREATED)
                    .entity(Map.of(
                            "ruleCode", result.ruleCode(),
                            "message", "Rule created successfully. Recompilation in progress."
                    ))
                    .build();

        } catch (Exception e) {
            span.recordException(e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Internal Server Error", "message", e.getMessage()))
                    .build();
        } finally {
            span.end();
        }
    }

    /**
     * Update an existing rule.
     *
     * @param ruleCode the rule code to update
     * @param rule the updated rule metadata
     * @return update response
     */
    @PUT
    @Path("/{ruleCode}")
    public Response updateRule(@PathParam("ruleCode") String ruleCode, RuleMetadata rule) {
        Span span = tracer.spanBuilder("http-update-rule").startSpan();
        try (Scope scope = span.makeCurrent()) {
            span.setAttribute("ruleCode", ruleCode);

            // Ensure ruleCode in path matches ruleCode in body
            if (!ruleCode.equals(rule.ruleCode())) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of(
                                "error", "Rule code mismatch",
                                "message", "Rule code in path (" + ruleCode + ") does not match rule code in body (" + rule.ruleCode() + ")"
                        ))
                        .build();
            }

            var result = ruleManagementService.updateRule(ruleCode, rule);

            if (!result.isValid()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of(
                                "error", "Validation failed",
                                "errors", result.errors()
                        ))
                        .build();
            }

            return Response.ok(Map.of(
                    "ruleCode", result.ruleCode(),
                    "message", "Rule updated successfully. Recompilation in progress."
            )).build();

        } catch (Exception e) {
            span.recordException(e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Internal Server Error", "message", e.getMessage()))
                    .build();
        } finally {
            span.end();
        }
    }

    /**
     * Delete a rule.
     *
     * @param ruleCode the rule code to delete
     * @return deletion response
     */
    @DELETE
    @Path("/{ruleCode}")
    public Response deleteRule(@PathParam("ruleCode") String ruleCode) {
        Span span = tracer.spanBuilder("http-delete-rule").startSpan();
        try (Scope scope = span.makeCurrent()) {
            span.setAttribute("ruleCode", ruleCode);

            boolean deleted = ruleManagementService.deleteRule(ruleCode);

            if (!deleted) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of(
                                "error", "Rule not found",
                                "ruleCode", ruleCode
                        ))
                        .build();
            }

            return Response.ok(Map.of(
                    "ruleCode", ruleCode,
                    "message", "Rule deleted successfully. Recompilation in progress."
            )).build();

        } catch (Exception e) {
            span.recordException(e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Internal Server Error", "message", e.getMessage()))
                    .build();
        } finally {
            span.end();
        }
    }

    /**
     * Delete multiple rules in batch.
     * This is much more efficient than deleting rules one by one.
     *
     * @param request the batch delete request containing rule codes to delete
     * @return batch deletion response with counts of deleted and failed rules
     */
    @POST
    @Path("/batch-delete")
    public Response deleteRulesBatch(BatchDeleteRequest request) {
        Span span = tracer.spanBuilder("http-batch-delete-rules").startSpan();
        try (Scope scope = span.makeCurrent()) {
            List<String> ruleCodes = request.ruleCodes();
            span.setAttribute("ruleCount", ruleCodes != null ? ruleCodes.size() : 0);

            if (ruleCodes == null || ruleCodes.isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of(
                                "error", "Bad Request",
                                "message", "No rule codes provided"
                        ))
                        .build();
            }

            List<String> deleted = new ArrayList<>();
            List<Map<String, String>> failed = new ArrayList<>();

            for (String ruleCode : ruleCodes) {
                try {
                    boolean success = ruleManagementService.deleteRule(ruleCode);
                    if (success) {
                        deleted.add(ruleCode);
                    } else {
                        failed.add(Map.of("ruleCode", ruleCode, "error", "Rule not found"));
                    }
                } catch (Exception e) {
                    failed.add(Map.of("ruleCode", ruleCode, "error", e.getMessage()));
                }
            }

            span.setAttribute("deletedCount", deleted.size());
            span.setAttribute("failedCount", failed.size());

            return Response.ok(Map.of(
                    "deleted", deleted,
                    "failed", failed,
                    "totalDeleted", deleted.size(),
                    "totalFailed", failed.size()
            )).build();

        } catch (Exception e) {
            span.recordException(e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Internal Server Error", "message", e.getMessage()))
                    .build();
        } finally {
            span.end();
        }
    }

    /**
     * Request body for batch delete operation.
     */
    public record BatchDeleteRequest(List<String> ruleCodes) {}

    /**
     * Validate a rule without persisting it.
     *
     * @param rule the rule to validate
     * @return validation result
     */
    @POST
    @Path("/validate")
    public Response validateRule(RuleMetadata rule) {
        Span span = tracer.spanBuilder("http-validate-rule").startSpan();
        try (Scope scope = span.makeCurrent()) {
            span.setAttribute("ruleCode", rule.ruleCode());

            var result = ruleManagementService.validateRule(rule);

            if (!result.isValid()) {
                return Response.ok(Map.of(
                        "valid", false,
                        "errors", result.errors()
                )).build();
            }

            return Response.ok(Map.of(
                    "valid", true,
                    "message", "Rule is valid"
            )).build();

        } catch (Exception e) {
            span.recordException(e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Internal Server Error", "message", e.getMessage()))
                    .build();
        } finally {
            span.end();
        }
    }

    // ========================================
    // Metadata & Query Operations
    // ========================================

    /**
     * List all rules with metadata.
     * Returns all rules from repository, including disabled/draft rules.
     *
     * @return collection of rule metadata
     */
    @GET
    public Response listRules() {
        Span span = tracer.spanBuilder("http-list-rules").startSpan();
        try (Scope scope = span.makeCurrent()) {
            // Get all rules from repository (includes disabled/draft rules)
            Collection<RuleMetadata> allRules = ruleManagementService.getAllRules();

            span.setAttribute("ruleCount", allRules.size());
            return Response.ok(allRules).build();

        } catch (Exception e) {
            span.recordException(e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Internal Server Error", "message", e.getMessage()))
                    .build();
        } finally {
            span.end();
        }
    }

    /**
     * Get metadata for a specific rule.
     *
     * @param ruleCode the rule code
     * @return rule metadata
     */
    @GET
    @Path("/{ruleCode}")
    public Response getRule(@PathParam("ruleCode") String ruleCode) {
        Span span = tracer.spanBuilder("http-get-rule").startSpan();
        try (Scope scope = span.makeCurrent()) {
            span.setAttribute("ruleCode", ruleCode);

            EngineModel model = modelManager.getEngineModel();
            RuleMetadata metadata = model.getRuleMetadata(ruleCode);

            if (metadata == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "Rule not found", "ruleCode", ruleCode))
                        .build();
            }

            return Response.ok(metadata).build();

        } catch (Exception e) {
            span.recordException(e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Internal Server Error", "message", e.getMessage()))
                    .build();
        } finally {
            span.end();
        }
    }

    /**
     * Get combination IDs for a specific rule.
     *
     * @param ruleCode the rule code
     * @return set of combination IDs
     */
    @GET
    @Path("/{ruleCode}/combinations")
    public Response getRuleCombinations(@PathParam("ruleCode") String ruleCode) {
        Span span = tracer.spanBuilder("http-get-rule-combinations").startSpan();
        try (Scope scope = span.makeCurrent()) {
            span.setAttribute("ruleCode", ruleCode);

            EngineModel model = modelManager.getEngineModel();
            Set<Integer> combinationIds = model.getCombinationIdsForRule(ruleCode);

            if (combinationIds.isEmpty()) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "Rule not found or has no combinations", "ruleCode", ruleCode))
                        .build();
            }

            return Response.ok(Map.of(
                    "ruleCode", ruleCode,
                    "combinationIds", combinationIds,
                    "combinationCount", combinationIds.size()
            )).build();

        } catch (Exception e) {
            span.recordException(e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Internal Server Error", "message", e.getMessage()))
                    .build();
        } finally {
            span.end();
        }
    }

    /**
     * Get rules using a specific predicate.
     *
     * @param predicateId the predicate ID
     * @return set of rule codes
     */
    @GET
    @Path("/by-predicate/{predicateId}")
    public Response getRulesByPredicate(@PathParam("predicateId") int predicateId) {
        Span span = tracer.spanBuilder("http-get-rules-by-predicate").startSpan();
        try (Scope scope = span.makeCurrent()) {
            span.setAttribute("predicateId", predicateId);

            EngineModel model = modelManager.getEngineModel();
            Set<String> ruleCodes = model.getRulesUsingPredicate(predicateId);

            return Response.ok(Map.of(
                    "predicateId", predicateId,
                    "ruleCodes", ruleCodes,
                    "ruleCount", ruleCodes.size()
            )).build();

        } catch (Exception e) {
            span.recordException(e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Internal Server Error", "message", e.getMessage()))
                    .build();
        } finally {
            span.end();
        }
    }

    /**
     * Filter rules by tag.
     *
     * @param tag the tag to filter by
     * @return collection of matching rule metadata
     */
    @GET
    @Path("/by-tag/{tag}")
    public Response getRulesByTag(@PathParam("tag") String tag) {
        Span span = tracer.spanBuilder("http-get-rules-by-tag").startSpan();
        try (Scope scope = span.makeCurrent()) {
            span.setAttribute("tag", tag);

            EngineModel model = modelManager.getEngineModel();
            Collection<RuleMetadata> allRules = model.getAllRuleMetadata();

            Collection<RuleMetadata> matchingRules = allRules.stream()
                    .filter(r -> r.tags() != null && r.tags().contains(tag))
                    .collect(Collectors.toList());

            span.setAttribute("matchCount", matchingRules.size());
            return Response.ok(Map.of(
                    "tag", tag,
                    "rules", matchingRules,
                    "matchCount", matchingRules.size()
            )).build();

        } catch (Exception e) {
            span.recordException(e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Internal Server Error", "message", e.getMessage()))
                    .build();
        } finally {
            span.end();
        }
    }

    // ========================================
    // Version History Operations
    // ========================================

    /**
     * Get all versions for a specific rule.
     *
     * @param ruleCode the rule code
     * @return list of rule versions
     */
    @GET
    @Path("/{ruleCode}/versions")
    public Response getRuleVersions(@PathParam("ruleCode") String ruleCode) {
        Span span = tracer.spanBuilder("http-get-rule-versions").startSpan();
        try (Scope scope = span.makeCurrent()) {
            span.setAttribute("ruleCode", ruleCode);

            // Check if rule exists
            if (!ruleManagementService.getRule(ruleCode).isPresent()) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "Rule not found", "ruleCode", ruleCode))
                        .build();
            }

            List<RuleVersion> versions = ruleManagementService.getRuleVersions(ruleCode);

            span.setAttribute("versionCount", versions.size());
            return Response.ok(Map.of(
                    "ruleCode", ruleCode,
                    "versions", versions,
                    "versionCount", versions.size()
            )).build();

        } catch (Exception e) {
            span.recordException(e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Internal Server Error", "message", e.getMessage()))
                    .build();
        } finally {
            span.end();
        }
    }

    /**
     * Get a specific version of a rule.
     *
     * @param ruleCode the rule code
     * @param version the version number
     * @return the rule version
     */
    @GET
    @Path("/{ruleCode}/versions/{version}")
    public Response getRuleVersion(
            @PathParam("ruleCode") String ruleCode,
            @PathParam("version") int version) {
        Span span = tracer.spanBuilder("http-get-rule-version").startSpan();
        try (Scope scope = span.makeCurrent()) {
            span.setAttribute("ruleCode", ruleCode);
            span.setAttribute("version", version);

            var ruleVersion = ruleManagementService.getRuleVersion(ruleCode, version);

            if (ruleVersion.isEmpty()) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of(
                                "error", "Version not found",
                                "ruleCode", ruleCode,
                                "version", version
                        ))
                        .build();
            }

            return Response.ok(ruleVersion.get()).build();

        } catch (Exception e) {
            span.recordException(e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Internal Server Error", "message", e.getMessage()))
                    .build();
        } finally {
            span.end();
        }
    }

    /**
     * Rollback a rule to a specific version.
     * Creates a new version with the state from the specified version.
     *
     * @param ruleCode the rule code
     * @param version the version to rollback to
     * @param request the rollback request containing author info
     * @return rollback result
     */
    @POST
    @Path("/{ruleCode}/versions/{version}/rollback")
    public Response rollbackRule(
            @PathParam("ruleCode") String ruleCode,
            @PathParam("version") int version,
            RollbackRequest request) {
        Span span = tracer.spanBuilder("http-rollback-rule").startSpan();
        try (Scope scope = span.makeCurrent()) {
            span.setAttribute("ruleCode", ruleCode);
            span.setAttribute("targetVersion", version);

            String author = request != null && request.author() != null ? request.author() : "system";
            var result = ruleManagementService.rollbackRule(ruleCode, version, author);

            if (!result.isValid()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of(
                                "error", "Rollback failed",
                                "errors", result.errors()
                        ))
                        .build();
            }

            return Response.ok(Map.of(
                    "ruleCode", ruleCode,
                    "restoredFromVersion", version,
                    "message", "Rule successfully rolled back to version " + version + ". Recompilation in progress."
            )).build();

        } catch (Exception e) {
            span.recordException(e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Internal Server Error", "message", e.getMessage()))
                    .build();
        } finally {
            span.end();
        }
    }

    /**
     * Compare two versions of a rule.
     *
     * @param ruleCode the rule code
     * @param version1 first version number
     * @param version2 second version number
     * @return comparison result with both versions
     */
    @GET
    @Path("/{ruleCode}/versions/compare")
    public Response compareVersions(
            @PathParam("ruleCode") String ruleCode,
            @QueryParam("v1") int version1,
            @QueryParam("v2") int version2) {
        Span span = tracer.spanBuilder("http-compare-versions").startSpan();
        try (Scope scope = span.makeCurrent()) {
            span.setAttribute("ruleCode", ruleCode);
            span.setAttribute("version1", version1);
            span.setAttribute("version2", version2);

            var v1 = ruleManagementService.getRuleVersion(ruleCode, version1);
            var v2 = ruleManagementService.getRuleVersion(ruleCode, version2);

            if (v1.isEmpty()) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "Version not found", "version", version1))
                        .build();
            }

            if (v2.isEmpty()) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "Version not found", "version", version2))
                        .build();
            }

            return Response.ok(Map.of(
                    "ruleCode", ruleCode,
                    "version1", v1.get(),
                    "version2", v2.get()
            )).build();

        } catch (Exception e) {
            span.recordException(e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Internal Server Error", "message", e.getMessage()))
                    .build();
        } finally {
            span.end();
        }
    }

    /**
     * Request body for rollback operation.
     */
    public record RollbackRequest(String author) {}
}
