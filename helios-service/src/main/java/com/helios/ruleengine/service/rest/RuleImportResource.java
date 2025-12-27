package com.helios.ruleengine.service.rest;

import com.helios.ruleengine.api.model.*;
import com.helios.ruleengine.service.service.RuleImportService;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * JAX-RS resource for rule import operations.
 * Provides endpoints for validating and executing rule imports.
 */
@Path("/rules/import")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class RuleImportResource {

    @Inject
    RuleImportService importService;

    @Inject
    Tracer tracer;

    /**
     * Validate rules before import.
     *
     * Parses and validates rules, checking for:
     * - Required fields
     * - Conflicts with existing rules
     * - Valid field values
     * - Performance concerns
     *
     * @param request validation request with rules to validate
     * @return validation response with status for each rule
     */
    @POST
    @Path("/validate")
    public Response validateImport(ImportValidationRequest request) {
        Span span = tracer.spanBuilder("http-import-validate").startSpan();
        try (Scope scope = span.makeCurrent()) {
            int ruleCount = request.rules() != null ? request.rules().size() : 0;
            span.setAttribute("ruleCount", ruleCount);
            span.setAttribute("format", request.format());

            ImportValidationResponse response = importService.validateImport(request);

            span.setAttribute("validRules", response.stats().valid());
            span.setAttribute("warnings", response.stats().warnings());
            span.setAttribute("errors", response.stats().errors());

            return Response.ok(response).build();

        } catch (Exception e) {
            span.recordException(e);
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Validation Failed", "message", e.getMessage()))
                    .build();
        } finally {
            span.end();
        }
    }

    /**
     * Execute rule import.
     *
     * Imports the selected rules according to the specified conflict resolution strategy.
     *
     * @param request execution request with rules to import
     * @return execution response with import results
     */
    @POST
    @Path("/execute")
    public Response executeImport(ImportExecutionRequest request) {
        Span span = tracer.spanBuilder("http-import-execute").startSpan();
        try (Scope scope = span.makeCurrent()) {
            span.setAttribute("ruleCount", request.rules().size());
            span.setAttribute("conflictResolution", request.conflictResolution().name());

            ImportExecutionResponse response = importService.executeImport(request);

            span.setAttribute("imported", response.imported());
            span.setAttribute("skipped", response.skipped());
            span.setAttribute("failed", response.failed());

            return Response.ok(response).build();

        } catch (Exception e) {
            span.recordException(e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Import Failed", "message", e.getMessage()))
                    .build();
        } finally {
            span.end();
        }
    }
}
