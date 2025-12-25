/*
 * Copyright (c) 2025 Helios Rule Engine
 * Licensed under the Apache License, Version 2.0
 */
package com.helios.ruleengine.service.rest;

import com.helios.ruleengine.service.service.RuleEvaluationService;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * JAX-RS resource for monitoring and metrics endpoints.
 * Provides access to system metrics, evaluator performance, and health information.
 */
@Path("/monitoring")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class MonitoringResource {

    @Inject
    RuleEvaluationService evaluationService;

    @Inject
    Tracer tracer;

    /**
     * Get current system metrics including evaluator performance.
     *
     * @return system metrics map
     */
    @GET
    @Path("/metrics")
    public Response getMetrics() {
        Span span = tracer.spanBuilder("http-get-monitoring-metrics").startSpan();
        try (Scope scope = span.makeCurrent()) {
            Map<String, Object> metrics = evaluationService.getMetrics();

            return Response.ok(metrics).build();

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
     * Get system health status.
     *
     * TODO: Implement comprehensive health checks including:
     * - Model load status
     * - Cache health
     * - Memory usage
     * - Thread pool status
     *
     * @return health status
     */
    @GET
    @Path("/health")
    public Response getHealth() {
        Span span = tracer.spanBuilder("http-get-health").startSpan();
        try (Scope scope = span.makeCurrent()) {
            // TODO: Add more comprehensive health checks
            return Response.ok(Map.of(
                    "status", "UP",
                    "timestamp", System.currentTimeMillis()
            )).build();

        } catch (Exception e) {
            span.recordException(e);
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("status", "DOWN", "error", e.getMessage()))
                    .build();
        } finally {
            span.end();
        }
    }
}
