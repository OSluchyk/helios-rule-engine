/*
 * Copyright (c) 2025 Helios Rule Engine
 * Licensed under the Apache License, Version 2.0
 */
package com.helios.ruleengine.service.rest;

import com.helios.ruleengine.api.CompilationListener;
import com.helios.ruleengine.api.model.EngineStats;
import com.helios.ruleengine.api.model.RuleDefinition;
import com.helios.ruleengine.api.model.RuleMetadata;
import com.helios.ruleengine.infra.management.EngineModelManager;
import com.helios.ruleengine.runtime.model.EngineModel;
import com.helios.ruleengine.service.repository.RuleRepository;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestStreamElementType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

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
    RuleRepository ruleRepository;

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
     * Get complete dictionaries with all mappings.
     *
     * @return both field and value dictionaries with ID-to-string mappings
     */
    @GET
    @Path("/dictionaries")
    public Response getDictionaries() {
        Span span = tracer.spanBuilder("http-get-dictionaries").startSpan();
        try (Scope scope = span.makeCurrent()) {
            EngineModel model = modelManager.getEngineModel();

            Map<String, String> fieldMappings = toDictionaryMap(model.getFieldDictionary());
            Map<String, String> valueMappings = toDictionaryMap(model.getValueDictionary());

            return Response.ok(Map.of(
                    "fields", Map.of(
                            "size", model.getFieldDictionary().size(),
                            "mappings", fieldMappings
                    ),
                    "values", Map.of(
                            "size", model.getValueDictionary().size(),
                            "mappings", valueMappings
                    )
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
     * Get field dictionary information (legacy endpoint for backward compatibility).
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
     * Get all unique predicates with details.
     *
     * @return list of all predicates with field names, operators, and values
     */
    @GET
    @Path("/predicates")
    public Response getPredicates() {
        Span span = tracer.spanBuilder("http-get-predicates").startSpan();
        try (Scope scope = span.makeCurrent()) {
            EngineModel model = modelManager.getEngineModel();
            var predicates = model.getUniquePredicates();
            var fieldDict = model.getFieldDictionary();
            var valueDict = model.getValueDictionary();

            List<Map<String, Object>> predicateList = new java.util.ArrayList<>();
            for (int i = 0; i < predicates.length; i++) {
                var predicate = predicates[i];
                String fieldName = fieldDict.decode(predicate.fieldId());
                String operator = predicate.operator().toString();

                // Decode value if it's an encoded string
                Object value = predicate.value();
                if (value instanceof Integer && predicate.operator().toString().contains("EQUAL")) {
                    String decodedValue = valueDict.decode((Integer) value);
                    if (decodedValue != null) {
                        value = decodedValue;
                    }
                }

                predicateList.add(Map.of(
                    "id", i,
                    "field", fieldName != null ? fieldName : "UNKNOWN",
                    "operator", operator,
                    "value", value != null ? value : "null",
                    "weight", predicate.weight(),
                    "selectivity", predicate.selectivity()
                ));
            }

            return Response.ok(Map.of(
                    "total", predicates.length,
                    "predicates", predicateList
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
     * Get unique predicates count (legacy endpoint for backward compatibility).
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

    /**
     * Get compilation progress stream.
     * Returns Server-Sent Events (SSE) with real-time compilation stage updates.
     *
     * @return SSE stream of compilation events
     */
    @GET
    @Path("/progress")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    public Multi<CompilationEvent> getCompilationProgress() {
        List<CompilationEvent> events = new CopyOnWriteArrayList<>();

        // Create a compilation listener that captures events
        CompilationListener listener = new CompilationListener() {
            @Override
            public void onStageStart(String stageName, int stageNumber, int totalStages) {
                events.add(new CompilationEvent(
                    "stage_start",
                    stageName,
                    stageNumber,
                    totalStages,
                    null,
                    null,
                    null
                ));
            }

            @Override
            public void onStageComplete(String stageName, StageResult result) {
                events.add(new CompilationEvent(
                    "stage_complete",
                    stageName,
                    null,
                    null,
                    result.durationMillis(),
                    result.metrics(),
                    null
                ));
            }

            @Override
            public void onError(String stageName, Exception error) {
                events.add(new CompilationEvent(
                    "error",
                    stageName,
                    null,
                    null,
                    null,
                    null,
                    error.getMessage()
                ));
            }
        };

        // Trigger recompilation in background with listener
        new Thread(() -> {
            try {
                modelManager.recompile(listener);
                events.add(new CompilationEvent(
                    "complete",
                    "COMPILATION",
                    null,
                    null,
                    null,
                    Map.of("success", true),
                    null
                ));
            } catch (Exception e) {
                events.add(new CompilationEvent(
                    "error",
                    "COMPILATION",
                    null,
                    null,
                    null,
                    null,
                    e.getMessage()
                ));
            }
        }).start();

        // Stream events as they are added
        return Multi.createFrom().iterable(events);
    }

    /**
     * Compile all rules from the database into the active engine model.
     * This will:
     * 1. Load all enabled rules from the database
     * 2. Compile them into a new EngineModel
     * 3. Replace the active model
     * 4. Update compilation_status for all compiled rules
     *
     * @return compilation result with statistics
     */
    @POST
    @Path("/compile-from-db")
    public Response compileFromDatabase() {
        Span span = tracer.spanBuilder("http-compile-from-db").startSpan();
        try (Scope scope = span.makeCurrent()) {
            // Load all rules from database
            List<RuleMetadata> allRules = ruleRepository.findAll();
            span.setAttribute("totalRules", allRules.size());

            // Filter to enabled rules only
            List<RuleMetadata> enabledRules = allRules.stream()
                    .filter(r -> r.enabled() != null && r.enabled())
                    .collect(Collectors.toList());
            span.setAttribute("enabledRules", enabledRules.size());

            if (enabledRules.isEmpty()) {
                return Response.ok(Map.of(
                        "success", false,
                        "message", "No enabled rules found in database",
                        "totalRules", allRules.size(),
                        "enabledRules", 0
                )).build();
            }

            // Convert RuleMetadata to RuleDefinition
            List<RuleDefinition> definitions = enabledRules.stream()
                    .map(this::toRuleDefinition)
                    .collect(Collectors.toList());

            // Compile rules
            long startTime = System.currentTimeMillis();
            modelManager.compileFromRules(definitions, null);
            long compilationTime = System.currentTimeMillis() - startTime;

            // Update compilation_status for all compiled rules
            int updatedCount = 0;
            for (RuleMetadata rule : enabledRules) {
                try {
                    RuleMetadata updated = rule.withCompilationMetadata(
                            rule.combinationIds(),
                            rule.estimatedSelectivity(),
                            rule.isVectorizable(),
                            "OK"
                    );
                    ruleRepository.save(updated);
                    updatedCount++;
                } catch (Exception e) {
                    // Log but don't fail the whole operation
                    span.recordException(e);
                }
            }

            EngineModel model = modelManager.getEngineModel();
            EngineStats stats = model.getStats();

            return Response.ok(Map.of(
                    "success", true,
                    "message", "Successfully compiled " + enabledRules.size() + " rules from database",
                    "totalRulesInDb", allRules.size(),
                    "enabledRules", enabledRules.size(),
                    "compiledRules", updatedCount,
                    "compilationTimeMs", compilationTime,
                    "uniqueCombinations", stats.uniqueCombinations(),
                    "totalPredicates", stats.totalPredicates()
            )).build();

        } catch (Exception e) {
            span.recordException(e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of(
                            "success", false,
                            "error", "Compilation failed",
                            "message", e.getMessage()
                    ))
                    .build();
        } finally {
            span.end();
        }
    }

    /**
     * Convert RuleMetadata to RuleDefinition for compilation.
     */
    private RuleDefinition toRuleDefinition(RuleMetadata metadata) {
        return new RuleDefinition(
                metadata.ruleCode(),
                metadata.conditions(),
                metadata.priority() != null ? metadata.priority() : 0,
                metadata.description(),
                metadata.enabled() != null ? metadata.enabled() : true
        );
    }

    /**
     * Helper method to convert a Dictionary to a Map of ID -> String.
     *
     * @param dict the dictionary to convert
     * @return map with string keys (ID) and string values
     */
    private Map<String, String> toDictionaryMap(com.helios.ruleengine.runtime.model.Dictionary dict) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < dict.size(); i++) {
            String decoded = dict.decode(i);
            if (decoded != null) {
                map.put(String.valueOf(i), decoded);
            }
        }
        return map;
    }

    /**
     * Compilation event for SSE streaming.
     */
    public record CompilationEvent(
        String type,           // stage_start, stage_complete, error, complete
        String stageName,
        Integer stageNumber,
        Integer totalStages,
        Long durationMs,
        Map<String, Object> metrics,
        String error
    ) {}
}
