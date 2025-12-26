/*
 * Copyright (c) 2025 Helios Rule Engine
 * Licensed under the Apache License, Version 2.0
 */
package com.helios.ruleengine.service.rest;

import com.helios.ruleengine.service.monitoring.RuleMetricsAggregator;
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
 * Provides access to system metrics, evaluator performance, health information,
 * and real-time rule evaluation statistics.
 */
@Path("/monitoring")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class MonitoringResource {

    @Inject
    RuleEvaluationService evaluationService;

    @Inject
    RuleMetricsAggregator metricsAggregator;

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

    /**
     * Get the top N most frequently evaluated rules (hot rules).
     *
     * @param topN Number of top rules to return (default: 10)
     * @return List of hot rules sorted by evaluation count
     */
    @GET
    @Path("/hot-rules")
    public Response getHotRules(@QueryParam("topN") @DefaultValue("10") int topN) {
        Span span = tracer.spanBuilder("http-get-hot-rules").startSpan();
        try (Scope scope = span.makeCurrent()) {
            span.setAttribute("topN", topN);

            var hotRules = metricsAggregator.getHotRules(topN);

            return Response.ok(Map.of(
                    "topN", topN,
                    "rules", hotRules,
                    "total", hotRules.size()
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
     * Get rules with slow P99 latency.
     *
     * @param thresholdMs P99 latency threshold in milliseconds (default: 100ms)
     * @return List of slow rules with P99 latency above threshold
     */
    @GET
    @Path("/slow-rules")
    public Response getSlowRules(@QueryParam("thresholdMs") @DefaultValue("100") long thresholdMs) {
        Span span = tracer.spanBuilder("http-get-slow-rules").startSpan();
        try (Scope scope = span.makeCurrent()) {
            span.setAttribute("thresholdMs", thresholdMs);

            long thresholdNanos = thresholdMs * 1_000_000;
            var slowRules = metricsAggregator.getSlowRules(thresholdNanos);

            return Response.ok(Map.of(
                    "thresholdMs", thresholdMs,
                    "rules", slowRules,
                    "total", slowRules.size()
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
     * Get latency history for the last hour.
     *
     * @return Time-series latency data (up to 3600 samples)
     */
    @GET
    @Path("/latency-history")
    public Response getLatencyHistory() {
        Span span = tracer.spanBuilder("http-get-latency-history").startSpan();
        try (Scope scope = span.makeCurrent()) {
            var history = metricsAggregator.getLatencyHistory();

            return Response.ok(Map.of(
                    "samples", history,
                    "total", history.size()
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
     * Get throughput history for the last hour.
     *
     * @return Time-series throughput data (events per second)
     */
    @GET
    @Path("/throughput-history")
    public Response getThroughputHistory() {
        Span span = tracer.spanBuilder("http-get-throughput-history").startSpan();
        try (Scope scope = span.makeCurrent()) {
            var history = metricsAggregator.getThroughputHistory();

            return Response.ok(Map.of(
                    "samples", history,
                    "total", history.size()
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
     * Get cache statistics.
     *
     * @return Cache hit rate and related metrics
     */
    @GET
    @Path("/cache-stats")
    public Response getCacheStats() {
        Span span = tracer.spanBuilder("http-get-cache-stats").startSpan();
        try (Scope scope = span.makeCurrent()) {
            double hitRate = metricsAggregator.getCacheHitRate();

            return Response.ok(Map.of(
                    "hitRate", hitRate,
                    "hitRatePercent", hitRate * 100
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
     * Get metrics summary (combines existing metrics with new aggregator data).
     *
     * @return Comprehensive metrics summary
     */
    @GET
    @Path("/summary")
    public Response getMetricsSummary() {
        Span span = tracer.spanBuilder("http-get-metrics-summary").startSpan();
        try (Scope scope = span.makeCurrent()) {
            var aggregatorSummary = metricsAggregator.getSummary();

            return Response.ok(aggregatorSummary).build();

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
     * Reset all metrics (for testing or manual reset).
     *
     * @return Success response
     */
    @POST
    @Path("/reset")
    public Response resetMetrics() {
        Span span = tracer.spanBuilder("http-post-reset-metrics").startSpan();
        try (Scope scope = span.makeCurrent()) {
            metricsAggregator.reset();

            return Response.ok(Map.of(
                    "status", "success",
                    "message", "All metrics have been reset"
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
