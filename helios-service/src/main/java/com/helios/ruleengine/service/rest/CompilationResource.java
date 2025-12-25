/*
 * Copyright (c) 2025 Helios Rule Engine
 * Licensed under the Apache License, Version 2.0
 */
package com.helios.ruleengine.service.rest;

import com.helios.ruleengine.api.model.EngineStats;
import com.helios.ruleengine.infra.management.EngineModelManager;
import com.helios.ruleengine.runtime.model.EngineModel;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * JAX-RS resource for compilation and model statistics endpoints.
 * Provides access to model stats, dictionaries, predicates, and deduplication info.
 */
@Path("/compilation")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CompilationResource {

    @Inject
    EngineModelManager modelManager;

    @Inject
    Tracer tracer;

    /**
     * Get engine model statistics.
     *
     * @return engine stats including deduplication metrics
     */
    @GET
    @Path("/stats")
    public Response getStats() {
        Span span = tracer.spanBuilder("http-get-stats").startSpan();
        try (Scope scope = span.makeCurrent()) {
            EngineModel model = modelManager.getEngineModel();
            EngineStats stats = model.getStats();

            return Response.ok(stats).build();

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
     * Get field dictionary information.
     *
     * @return field dictionary size and sample entries
     */
    @GET
    @Path("/dictionaries/fields")
    public Response getFieldDictionary() {
        Span span = tracer.spanBuilder("http-get-field-dictionary").startSpan();
        try (Scope scope = span.makeCurrent()) {
            EngineModel model = modelManager.getEngineModel();
            int size = model.getFieldDictionary().size();

            return Response.ok(Map.of(
                    "type", "field",
                    "size", size,
                    "description", "Dictionary encoding for field names"
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
     * Get value dictionary information.
     *
     * @return value dictionary size and sample entries
     */
    @GET
    @Path("/dictionaries/values")
    public Response getValueDictionary() {
        Span span = tracer.spanBuilder("http-get-value-dictionary").startSpan();
        try (Scope scope = span.makeCurrent()) {
            EngineModel model = modelManager.getEngineModel();
            int size = model.getValueDictionary().size();

            return Response.ok(Map.of(
                    "type", "value",
                    "size", size,
                    "description", "Dictionary encoding for string values"
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
     * Get unique predicates count.
     *
     * @return predicate count
     */
    @GET
    @Path("/predicates/count")
    public Response getPredicateCount() {
        Span span = tracer.spanBuilder("http-get-predicate-count").startSpan();
        try (Scope scope = span.makeCurrent()) {
            EngineModel model = modelManager.getEngineModel();
            int count = model.getUniquePredicates().length;

            return Response.ok(Map.of(
                    "predicateCount", count,
                    "description", "Number of unique predicates in the compiled model"
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
     * Get deduplication analysis.
     *
     * @return deduplication statistics
     */
    @GET
    @Path("/deduplication")
    public Response getDeduplicationAnalysis() {
        Span span = tracer.spanBuilder("http-get-deduplication").startSpan();
        try (Scope scope = span.makeCurrent()) {
            EngineModel model = modelManager.getEngineModel();
            EngineStats stats = model.getStats();
            Map<String, Object> metadata = stats.metadata();

            int logicalRules = (int) metadata.getOrDefault("logicalRules", 0);
            int uniqueCombinations = stats.uniqueCombinations();
            double dedupRate = (double) metadata.getOrDefault("deduplicationRatePercent", 0.0);

            return Response.ok(Map.of(
                    "logicalRules", logicalRules,
                    "uniqueCombinations", uniqueCombinations,
                    "deduplicationRatePercent", dedupRate,
                    "savings", String.format("%.1f%% reduction in physical rules", dedupRate)
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
