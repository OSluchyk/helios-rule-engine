// File: src/main/java/com/google/ruleengine/server/HttpServer.java
package os.toolset.ruleengine.server;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import os.toolset.ruleengine.core.EngineModel;
import os.toolset.ruleengine.core.RuleEvaluator;
import os.toolset.ruleengine.model.Event;
import os.toolset.ruleengine.model.MatchResult;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.logging.Logger;

/**
 * High-performance HTTP server using JDK HttpServer.
 * Handles REST endpoints for rule evaluation and monitoring.
 */
public class HttpServer {
    private static final Logger logger = Logger.getLogger(HttpServer.class.getName());

    private final com.sun.net.httpserver.HttpServer server;
    private final RuleEvaluator evaluator;
    private final EngineModel model;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ThreadPoolExecutor executor;

    public HttpServer(int port, RuleEvaluator evaluator, EngineModel model) throws IOException {
        this.evaluator = evaluator;
        this.model = model;

        // Create thread pool for handling requests
        this.executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(
                Runtime.getRuntime().availableProcessors() * 2);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        // Create HTTP server
        this.server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress(port), 100);

        // Register handlers
        server.createContext("/evaluate", new EvaluateHandler());
        server.createContext("/health", new HealthHandler());
        server.createContext("/metrics", new MetricsHandler());
        server.createContext("/rules", new RulesHandler());

        server.setExecutor(executor);

        logger.info("HTTP server initialized on port " + port);
    }

    public void start() {
        server.start();
        logger.info("HTTP server started");
    }

    public void stop() {
        server.stop(0);
        executor.shutdown();
        logger.info("HTTP server stopped");
    }

    /**
     * Handler for /evaluate endpoint - evaluates events against rules.
     */
    private class EvaluateHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, Map.of("error", "Method not allowed"));
                return;
            }

            try {
                // Parse request body
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Map<String, Object> request = objectMapper.readValue(body, Map.class);

                // Extract event data
                String eventId = (String) request.getOrDefault("event_id", java.util.UUID.randomUUID().toString());
                String eventType = (String) request.get("event_type");
                Map<String, Object> attributes = (Map<String, Object>) request.getOrDefault("attributes", Map.of());

                // Create and evaluate event
                Event event = new Event(eventId, eventType, attributes);
                MatchResult result = evaluator.evaluate(event);

                // Send response
                sendResponse(exchange, 200, result);

            } catch (Exception e) {
                logger.severe("Error evaluating event: " + e.getMessage());
                sendResponse(exchange, 400, Map.of("error", e.getMessage()));
            }
        }
    }

    /**
     * Handler for /health endpoint - returns server health status.
     */
    private class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Map<String, Object> health = Map.of(
                    "status", "UP",
                    "timestamp", System.currentTimeMillis(),
                    "rules_loaded", model.getStats().totalRules(),
                    "predicates_registered", model.getStats().totalPredicates(),
                    "thread_pool", Map.of(
                            "active", executor.getActiveCount(),
                            "pool_size", executor.getPoolSize(),
                            "queue_size", executor.getQueue().size()
                    )
            );

            sendResponse(exchange, 200, health);
        }
    }

    /**
     * Handler for /metrics endpoint - returns performance metrics.
     */
    private class MetricsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Map<String, Object> metrics = Map.of(
                    "evaluator", evaluator.getMetrics().getSnapshot(),
                    "model", Map.of(
                            "total_internal_rules", model.getStats().totalRules(),
                            "total_predicates", model.getStats().totalPredicates(),
                            "compilation_time_ms", model.getStats().compilationTimeNanos() / 1_000_000,
                            "metadata", model.getStats().metadata()
                    ),
                    "jvm", Map.of(
                            "heap_used_mb", (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024,
                            "heap_max_mb", Runtime.getRuntime().maxMemory() / 1024 / 1024,
                            "processors", Runtime.getRuntime().availableProcessors()
                    )
            );

            sendResponse(exchange, 200, metrics);
        }
    }

    /**
     * Handler for /rules endpoint - returns loaded rules information.
     */
    private class RulesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("GET".equals(exchange.getRequestMethod())) {
                // Return summary of loaded rules
                Map<String, Object> summary = Map.of(
                        "total_internal_rules", model.getRuleStore().length,
                        "total_predicates", model.getPredicateRegistry().size(),
                        "rules", java.util.Arrays.stream(model.getRuleStore())
                                .map(rule -> Map.of(
                                        "id", rule.getId(),
                                        "code", rule.getRuleCode(),
                                        "predicate_count", rule.getPredicateCount(),
                                        "priority", rule.getPriority()
                                ))
                                .toList()
                );

                sendResponse(exchange, 200, summary);
            } else {
                sendResponse(exchange, 405, Map.of("error", "Method not allowed"));
            }
        }
    }

    private void sendResponse(HttpExchange exchange, int statusCode, Object response) throws IOException {
        String json = objectMapper.writeValueAsString(response);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
