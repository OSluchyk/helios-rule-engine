package com.helios.ruleengine.service.rest;

import com.helios.ruleengine.api.model.Event;
import com.helios.ruleengine.api.model.EvaluationResult;
import com.helios.ruleengine.api.model.ExplanationResult;
import com.helios.ruleengine.api.model.MatchResult;
import com.helios.ruleengine.service.service.RuleEvaluationService;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
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
     * Evaluate an event with detailed execution tracing.
     *
     * <p><b>Performance Impact:</b> ~10% overhead. Use for debugging only.
     *
     * @param event the event to evaluate
     * @return evaluation result with trace data
     */
    @POST
    @Path("/evaluate/trace")
    public Response evaluateWithTrace(Event event) {
        Span span = tracer.spanBuilder("http-evaluate-trace").startSpan();
        try (Scope scope = span.makeCurrent()) {
            span.setAttribute("eventId", event.eventId());

            EvaluationResult result = evaluationService.evaluateWithTrace(event);
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
     * Explain why a specific rule matched or didn't match an event.
     *
     * @param event the event to evaluate
     * @param ruleCode the rule to explain
     * @return explanation result
     */
    @POST
    @Path("/evaluate/explain/{ruleCode}")
    public Response explainRule(@PathParam("ruleCode") String ruleCode, Event event) {
        Span span = tracer.spanBuilder("http-explain-rule").startSpan();
        try (Scope scope = span.makeCurrent()) {
            span.setAttribute("eventId", event.eventId());
            span.setAttribute("ruleCode", ruleCode);

            ExplanationResult result = evaluationService.explainRule(event, ruleCode);
            return Response.ok(result).build();

        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Rule not found", "message", e.getMessage()))
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
     * Evaluate multiple events in batch.
     *
     * @param events list of events to evaluate
     * @return list of match results
     */
    @POST
    @Path("/evaluate/batch")
    public Response evaluateBatch(List<Event> events) {
        Span span = tracer.spanBuilder("http-evaluate-batch").startSpan();
        try (Scope scope = span.makeCurrent()) {
            span.setAttribute("eventCount", events.size());

            List<MatchResult> results = evaluationService.evaluateBatch(events);
            return Response.ok(results).build();

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
