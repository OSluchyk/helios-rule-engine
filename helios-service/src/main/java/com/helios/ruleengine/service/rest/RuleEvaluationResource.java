package com.helios.ruleengine.service.rest;

import com.helios.ruleengine.api.model.Event;
import com.helios.ruleengine.api.model.EvaluationResult;
import com.helios.ruleengine.api.model.ExplanationResult;
import com.helios.ruleengine.api.model.MatchResult;
import com.helios.ruleengine.api.model.TraceLevel;
import com.helios.ruleengine.api.model.BatchEvaluationResult;
import com.helios.ruleengine.api.model.BatchStats;
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
 * Provides evaluation with optional tracing and explanations for debugging.
 */
@Path("/evaluate")
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
     * <p><b>Performance Impact:</b> Varies by trace level:
     * <ul>
     *   <li>BASIC: ~34% overhead - Rule matches only</li>
     *   <li>STANDARD: ~51% overhead - Rule + predicate outcomes</li>
     *   <li>FULL: ~53% overhead - All details + field values</li>
     * </ul>
     *
     * @param event the event to evaluate
     * @param level trace detail level (default: FULL)
     * @param conditionalTracing only generate trace if rules match (default: false)
     * @return evaluation result with trace data
     */
    @POST
    @Path("/trace")
    public Response evaluateWithTrace(
            Event event,
            @QueryParam("level") @DefaultValue("FULL") TraceLevel level,
            @QueryParam("conditionalTracing") @DefaultValue("false") boolean conditionalTracing) {

        Span span = tracer.spanBuilder("http-evaluate-trace").startSpan();
        try (Scope scope = span.makeCurrent()) {
            span.setAttribute("eventId", event.eventId());
            span.setAttribute("traceLevel", level.name());
            span.setAttribute("conditionalTracing", conditionalTracing);

            EvaluationResult result = evaluationService.evaluateWithTrace(event, level, conditionalTracing);
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
     * <p>Provides human-readable explanation with:
     * <ul>
     *   <li>Which conditions passed/failed</li>
     *   <li>Actual field values from the event</li>
     *   <li>"Closeness" metric for near-misses</li>
     * </ul>
     *
     * @param ruleCode the rule to explain
     * @param event the event to evaluate
     * @return explanation result with condition-level details
     */
    @POST
    @Path("/explain/{ruleCode}")
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
     * Evaluate multiple events in batch with aggregated statistics.
     *
     * <p>Returns individual match results plus batch-level metrics:
     * <ul>
     *   <li>Average evaluation time</li>
     *   <li>Match rate (% of events with matches)</li>
     *   <li>Min/max evaluation times</li>
     *   <li>Total and average matched rules</li>
     * </ul>
     *
     * @param events list of events to evaluate
     * @return batch evaluation result with statistics
     */
    @POST
    @Path("/batch")
    public Response evaluateBatch(List<Event> events) {
        Span span = tracer.spanBuilder("http-evaluate-batch").startSpan();
        try (Scope scope = span.makeCurrent()) {
            span.setAttribute("eventCount", events.size());

            BatchEvaluationResult result = evaluationService.evaluateBatchWithStats(events);

            span.setAttribute("matchRate", result.stats().matchRate());
            span.setAttribute("avgEvaluationTimeNanos", result.stats().avgEvaluationTimeNanos());

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
}
