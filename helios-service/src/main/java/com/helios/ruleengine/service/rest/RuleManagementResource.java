/*
 * Copyright (c) 2025 Helios Rule Engine
 * Licensed under the Apache License, Version 2.0
 */
package com.helios.ruleengine.service.rest;

import com.helios.ruleengine.api.model.RuleMetadata;
import com.helios.ruleengine.infra.management.EngineModelManager;
import com.helios.ruleengine.runtime.model.EngineModel;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * JAX-RS resource for rule management and metadata endpoints.
 * Provides access to rule metadata, combination mappings, and predicate usage.
 */
@Path("/rules")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class RuleManagementResource {

    @Inject
    EngineModelManager modelManager;

    @Inject
    Tracer tracer;

    /**
     * List all rules with metadata.
     *
     * @return collection of rule metadata
     */
    @GET
    public Response listRules() {
        Span span = tracer.spanBuilder("http-list-rules").startSpan();
        try (Scope scope = span.makeCurrent()) {
            EngineModel model = modelManager.getEngineModel();
            Collection<RuleMetadata> allRules = model.getAllRuleMetadata();

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
}
