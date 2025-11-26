/*
 * Copyright (c) 2025 Helios Rule Engine
 * Licensed under the Apache License, Version 2.0
 */
package com.helios.ruleengine.infra.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helios.ruleengine.api.model.Event;
import com.helios.ruleengine.api.model.MatchResult;
import com.helios.ruleengine.infra.management.EngineModelManager;
import com.helios.ruleengine.infra.telemetry.TracingService;
import com.helios.ruleengine.runtime.evaluation.RuleEvaluator;
import com.helios.ruleengine.runtime.model.EngineModel;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;

/**
 * High-performance, lightweight HTTP server for the Helios Rule Engine.
 *
 * <h2>Architecture</h2>
 * <p>This server uses a ThreadLocal pool for RuleEvaluator instances.
 * A RuleEvaluator is tied to a specific EngineModel. The pool checks if the
 * model has been hot-reloaded and only creates a new evaluator when necessary.
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>POST /evaluate - Evaluate an event against rules</li>
 *   <li>GET /health - Health check</li>
 *   <li>GET /metrics - Evaluator metrics</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. The ThreadLocal evaluator pool ensures
 * each thread has its own evaluator instance.
 */
public class HttpServer {

    private final com.sun.net.httpserver.HttpServer server;
    private final Tracer tracer;
    private final ObjectMapper objectMapper;
    private final EngineModelManager modelManager;

    /**
     * Thread-local pool for RuleEvaluator instances.
     *
     * <p>The RuleEvaluator is stateless (caches are on the EngineModel),
     * but it is tied to a specific EngineModel instance. This pool ensures
     * that we only create a new RuleEvaluator when:
     * <ol>
     *   <li>A thread handles its first request</li>
     *   <li>The EngineModel has been hot-reloaded by the EngineModelManager</li>
     * </ol>
     */
    private final ThreadLocal<RuleEvaluator> evaluatorPool;

    /**
     * Creates a new HttpServer.
     *
     * @param port         the port to listen on
     * @param modelManager the engine model manager for hot-reload support
     * @param tracer       OpenTelemetry tracer for observability
     * @throws IOException if the server cannot be created
     */
    public HttpServer(int port, EngineModelManager modelManager, Tracer tracer) throws IOException {
        this.modelManager = Objects.requireNonNull(modelManager, "EngineModelManager cannot be null");
        this.tracer = Objects.requireNonNull(tracer, "Tracer cannot be null");
        this.objectMapper = new ObjectMapper();
        this.server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(port), 0);

        // Initialize the ThreadLocal pool
        this.evaluatorPool = ThreadLocal.withInitial(() -> {
            System.out.println("Initializing new RuleEvaluator for thread: " + Thread.currentThread().getName());
            return createNewEvaluator(modelManager.getEngineModel());
        });

        // Set up server contexts
        this.server.createContext("/evaluate", new EvaluationHandler());
        this.server.createContext("/health", new HealthHandler());
        this.server.createContext("/metrics", new MetricsHandler());

        // Use a fixed thread pool for handling requests
        int coreCount = Runtime.getRuntime().availableProcessors();
        this.server.setExecutor(Executors.newFixedThreadPool(coreCount * 2));
    }

    /**
     * Helper method to create a new, properly configured RuleEvaluator.
     */
    private RuleEvaluator createNewEvaluator(EngineModel model) {
        // The 'true' enables the BaseConditionEvaluator, which uses its own separate cache.
        return new RuleEvaluator(model, tracer, true);
    }

    /**
     * Starts the HTTP server.
     */
    public void start() {
        this.modelManager.start(); // Start the file watcher
        this.server.start();
        System.out.println("Helios Rule Engine server started on port 8080.");
        System.out.println("Endpoints: /evaluate (POST), /health (GET), /metrics (GET)");
    }

    /**
     * Stops the HTTP server.
     */
    public void stop(int delay) {
        System.out.println("Stopping server...");
        this.modelManager.shutdown(); // Stop the file watcher
        this.server.stop(delay);
    }

    /**
     * HTTP Handler for rule evaluations.
     * This is the hot path and is highly optimized.
     */
    class EvaluationHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                return;
            }

            Span span = tracer.spanBuilder("http-evaluate").startSpan();
            try (Scope scope = span.makeCurrent()) {

                // 1. Get the most up-to-date model from the manager
                EngineModel currentModel = modelManager.getEngineModel();

                // 2. Get the evaluator from the thread-local pool and check for staleness
                RuleEvaluator evaluator = evaluatorPool.get();
                if (evaluator.getModel() != currentModel) {
                    // The model has changed (hot-reload).
                    // Create a new evaluator for the new model and update the pool.
                    span.addEvent("Stale model detected. Creating new evaluator.");
                    evaluator = createNewEvaluator(currentModel);
                    evaluatorPool.set(evaluator);
                }

                // 3. Parse the event from request body
                Event event;
                try (InputStream is = exchange.getRequestBody()) {
                    event = objectMapper.readValue(is, Event.class);
                }

                // FIX: Use record accessor eventId() instead of getEventId()
                span.setAttribute("eventId", event.eventId());

                // 4. Evaluate using the correct, pooled, up-to-date evaluator
                MatchResult result = evaluator.evaluate(event);

                // 5. Send the response
                String responseBody = objectMapper.writeValueAsString(result);
                sendResponse(exchange, 200, responseBody);

            } catch (Exception e) {
                span.recordException(e);
                System.err.println("Error during evaluation: " + e.getMessage());
                e.printStackTrace();
                sendResponse(exchange, 500, "{\"error\":\"Internal Server Error\"}");
            } finally {
                span.end();
            }
        }
    }

    /**
     * Simple health check handler.
     */
    class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                return;
            }
            // Check if the model manager has a valid model
            if (modelManager.getEngineModel() != null && modelManager.getEngineModel().getNumRules() > 0) {
                sendResponse(exchange, 200, "{\"status\":\"UP\"}");
            } else {
                sendResponse(exchange, 503, "{\"status\":\"DOWN\", \"reason\":\"EngineModel not loaded\"}");
            }
        }
    }

    /**
     * Metrics handler to expose internal evaluator metrics.
     */
    class MetricsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                return;
            }

            try {
                RuleEvaluator evaluator = evaluatorPool.get();
                Map<String, Object> metrics = evaluator.getDetailedMetrics();
                String responseBody = objectMapper.writeValueAsString(metrics);
                sendResponse(exchange, 200, responseBody);
            } catch (Exception e) {
                System.err.println("Error getting metrics: " + e.getMessage());
                sendResponse(exchange, 500, "{\"error\":\"Internal Server Error\"}");
            }
        }
    }

    /**
     * Helper method to send HTTP responses.
     */
    private void sendResponse(HttpExchange exchange, int statusCode, String body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        byte[] responseBytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, responseBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBytes);
        }
    }
}