package com.helios.ruleengine.service.rest;

import com.helios.ruleengine.api.model.Event;
import com.helios.ruleengine.api.model.MatchResult;
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
 * JAX-RS resource for rule evaluation endpoints.
 * Replaces the custom HttpServer handlers with standard REST endpoints.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class RuleEvaluationResource {

    @Inject
    RuleEvaluationService evaluationService;

    @Inject
    Tracer tracer;

    /**
     * Evaluate an event against the rule engine.
     *
     * @param event the event to evaluate
     * @return the match result containing matched rules
     */
    @POST
    @Path("/evaluate")
    public Response evaluate(Event event) {
        Span span = tracer.spanBuilder("http-evaluate").startSpan();
        try (Scope scope = span.makeCurrent()) {
            span.setAttribute("eventId", event.eventId());

            MatchResult result = evaluationService.evaluate(event);
            return Response.ok(result).build();

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
     * Get detailed metrics from the rule evaluator.
     *
     * @return evaluator metrics
     */
    @GET
    @Path("/metrics")
    public Response getMetrics() {
        try {
            Map<String, Object> metrics = evaluationService.getMetrics();
            return Response.ok(metrics).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Internal Server Error", "message", e.getMessage()))
                    .build();
        }
    }
}
