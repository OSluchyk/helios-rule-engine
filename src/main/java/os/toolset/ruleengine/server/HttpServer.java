package os.toolset.ruleengine.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import os.toolset.ruleengine.core.EngineModel;
import os.toolset.ruleengine.core.EngineModelManager;
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
import java.util.stream.IntStream;

public class HttpServer {
    private static final Logger logger = Logger.getLogger(HttpServer.class.getName());

    private final com.sun.net.httpserver.HttpServer server;
    private final EngineModelManager modelManager;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ThreadPoolExecutor executor;

    public HttpServer(int port, EngineModelManager modelManager) throws IOException {
        this.modelManager = modelManager;
        this.executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 2);
        this.server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(port), 100);

        server.createContext("/evaluate", new EvaluateHandler());
        server.createContext("/health", new HealthHandler());
        server.createContext("/metrics", new MetricsHandler());
        server.createContext("/rules", new RulesHandler());
        server.setExecutor(executor);

        logger.info("HTTP server initialized on port " + port);
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
        executor.shutdown();
    }

    private class EvaluateHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, Map.of("error", "Method not allowed"));
                return;
            }

            try {
                // Get the most current model and create a fresh evaluator for this request
                EngineModel currentModel = modelManager.getEngineModel();
                RuleEvaluator evaluator = new RuleEvaluator(currentModel);

                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Map<String, Object> request = objectMapper.readValue(body, Map.class);

                String eventId = (String) request.getOrDefault("event_id", java.util.UUID.randomUUID().toString());
                String eventType = (String) request.get("event_type");
                Map<String, Object> attributes = (Map<String, Object>) request.getOrDefault("attributes", Map.of());

                Event event = new Event(eventId, eventType, attributes);
                MatchResult result = evaluator.evaluate(event);

                sendResponse(exchange, 200, result);
            } catch (Exception e) {
                logger.severe("Error evaluating event: " + e.getMessage());
                sendResponse(exchange, 400, Map.of("error", e.getMessage()));
            }
        }
    }

    // Other handlers are updated to get the current model
    private class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            EngineModel currentModel = modelManager.getEngineModel();
            sendResponse(exchange, 200, Map.of("status", "UP", "rules_loaded", currentModel.getNumRules()));
        }
    }

    private class MetricsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            EngineModel currentModel = modelManager.getEngineModel();
            sendResponse(exchange, 200, Map.of("model", Map.of("total_unique_combinations", currentModel.getNumRules())));
        }
    }

    private class RulesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            EngineModel currentModel = modelManager.getEngineModel();
            int numCombinations = currentModel.getNumRules();
            Map<String, Object> summary = Map.of(
                    "total_unique_combinations", numCombinations,
                    "combinations", IntStream.range(0, numCombinations)
                            .mapToObj(i -> Map.of(
                                    "id", i, "code", currentModel.getCombinationRuleCode(i),
                                    "priority", currentModel.getCombinationPriority(i)))
                            .toList()
            );
            sendResponse(exchange, 200, summary);
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