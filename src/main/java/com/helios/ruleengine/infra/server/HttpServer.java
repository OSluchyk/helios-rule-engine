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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;

/**
 * High-performance, lightweight HTTP server for the Helios Rule Engine.
 *
 * ✅ RECOMMENDATION 2 FIX (HIGH PRIORITY):
 * This server now implements a ThreadLocal pool for RuleEvaluator instances.
 * A RuleEvaluator is tied to a specific EngineModel. This pool checks if the
 * model has been hot-reloaded and only creates a new evaluator when necessary.
 *
 * This change prevents the creation of a new RuleEvaluator (and all its
 * sub-components) for every request, dramatically improving performance
 * and allowing the BaseConditionEvaluator and EligiblePredicateSet caches
 * to function effectively.
 */
public class HttpServer {

    private final com.sun.net.httpserver.HttpServer server;
    private final Tracer tracer;
    private final ObjectMapper objectMapper;
    private final EngineModelManager modelManager;

    /**
     * ✅ RECOMMENDATION 2 FIX (PART 1)
     *
     * Thread-local pool for RuleEvaluator instances.
     * The RuleEvaluator is now stateless (caches are on the EngineModel),
     * but it is *tied to a specific EngineModel instance*.
     * This pool ensures that we only create a new RuleEvaluator when:
     * 1. A thread handles its first request.
     * 2. The EngineModel has been hot-reloaded by the EngineModelManager.
     *
     * This avoids creating a new RuleEvaluator (and all its sub-components
     * like PredicateEvaluator) for every single HTTP request.
     */
    private final ThreadLocal<RuleEvaluator> evaluatorPool;

    public HttpServer(int port, EngineModelManager modelManager, Tracer tracer) throws IOException {
        this.modelManager = Objects.requireNonNull(modelManager, "EngineModelManager cannot be null");
        this.tracer = Objects.requireNonNull(tracer, "Tracer cannot be null");
        this.objectMapper = new ObjectMapper();
        this.server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(port), 0);

        /**
         * ✅ RECOMMENDATION 2 FIX (PART 2)
         * Initialize the ThreadLocal pool.
         * It creates an evaluator with the *current* model for this new thread.
         * The evaluator's internal state (like its cache) is now shared via the EngineModel.
         */
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
     * ✅ RECOMMENDATION 2 FIX (PART 3)
     * Helper method to create a new, properly configured RuleEvaluator.
     * The 'true' flag enables the BaseConditionEvaluator, which is critical for performance.
     */
    private RuleEvaluator createNewEvaluator(EngineModel model) {
        // The RuleEvaluator is now lightweight.
        // It gets all shared caches (like eligiblePredicateSetCache) from the EngineModel.
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
            // ✅ BUG FIX: Correctly scope the span
            try (Scope scope = span.makeCurrent()) {

                // 1. Get the most up-to-date model from the manager
                EngineModel currentModel = modelManager.getEngineModel();

                /**
                 * ✅ RECOMMENDATION 2 FIX (PART 4)
                 * Get the evaluator from the thread-local pool and check for staleness.
                 */
                RuleEvaluator evaluator = evaluatorPool.get();
                if (evaluator.getModel() != currentModel) {
                    // The model has changed (hot-reload).
                    // Create a new evaluator for the new model and update the pool.
                    span.addEvent("Stale model detected. Creating new evaluator.");
                    evaluator = createNewEvaluator(currentModel);
                    evaluatorPool.set(evaluator);
                }
                // --- End of Fix ---

                // 2. Parse the event
                // ✅ BUG FIX: Correctly handle the InputStream in its own try-with-resources
                Event event;
                try (InputStream is = exchange.getRequestBody()) {
                    event = objectMapper.readValue(is, Event.class);
                }
                span.setAttribute("eventId", event.getEventId());

                // 3. Evaluate using the correct, pooled, up-to-date evaluator
                // This call uses the pooled EvaluationContext internally (from Rec 1)
                // and the shared EligiblePredicateCache (from Rec 2).
                MatchResult result = evaluator.evaluate(event);

                // 4. Send the response
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
                // ✅ RECOMMENDATION 2 FIX: Get metrics from this thread's current evaluator
                // This ensures metrics are from an up-to-date evaluator
                RuleEvaluator evaluator = evaluatorPool.get();

                // Check for staleness just in case
                EngineModel currentModel = modelManager.getEngineModel();
                if (evaluator.getModel() != currentModel) {
                    evaluator = createNewEvaluator(currentModel);
                    evaluatorPool.set(evaluator);
                }

                Map<String, Object> metrics = evaluator.getDetailedMetrics();
                String responseBody = objectMapper.writeValueAsString(metrics);
                sendResponse(exchange, 200, responseBody);
            } catch (Exception e) {
                sendResponse(exchange, 500, "{\"error\":\"Could not serialize metrics\"}");
            }
        }
    }

    /**
     * Helper method to send a standardized JSON response.
     */
    private void sendResponse(HttpExchange exchange, int statusCode, String responseBody) throws IOException {
        try {
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            byte[] responseBytes = responseBody.getBytes("UTF-8");
            exchange.sendResponseHeaders(statusCode, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        } catch (Exception e) {
            // This can happen if the client closes the connection
            System.err.println("Error sending response: " + e.getMessage());
        }
    }

    /**
     * Main entry point to run the server.
     */
    public static void main(String[] args) {
        try {
            // Default to "rules.json" in the current directory if no arg is given
            Path rulesPath = Paths.get(args.length > 0 ? args[0] : "rules.json");
            if (!Files.exists(rulesPath)) {
                System.err.println("Error: Rules file not found at " + rulesPath.toAbsolutePath());
                System.err.println("Please specify the path to your rules.json file as an argument.");
                System.exit(1);
            }

            Tracer tracer = TracingService.getInstance().getTracer();

            // The EngineModelManager will watch the rulesPath for changes
            EngineModelManager modelManager = new EngineModelManager(rulesPath, tracer);

            int port = Integer.parseInt(System.getProperty("server.port", "8080"));
            HttpServer server = new HttpServer(port, modelManager, tracer);
            server.start();

            // Add a shutdown hook to gracefully stop the server
            Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));

        } catch (Exception e) {
            System.err.println("Failed to start server: " + e.getMessage());
            e.printStackTrace();
        }
    }
}