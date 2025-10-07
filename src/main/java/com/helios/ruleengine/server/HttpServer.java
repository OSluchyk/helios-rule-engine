package com.helios.ruleengine.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helios.ruleengine.core.evaluation.DefaultRuleEvaluator;
import com.helios.ruleengine.core.management.EngineModelManager;
import com.helios.ruleengine.core.model.EngineModel;
import com.helios.ruleengine.model.Event;
import com.helios.ruleengine.model.MatchResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

public class HttpServer {
    private final com.sun.net.httpserver.HttpServer server;
    private final EngineModelManager modelManager;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Tracer tracer;

    public HttpServer(int port, EngineModelManager modelManager, Tracer tracer) throws IOException {
        this.modelManager = modelManager;
        this.tracer = tracer;
        ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 2);
        this.server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(port), 100);
        server.createContext("/evaluate", new EvaluateHandler());
        server.setExecutor(executor);
    }

    public void start() { server.start(); }
    public void stop() { server.stop(0); }

    private class EvaluateHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Span span = tracer.spanBuilder("POST /evaluate").startSpan();
            try (Scope scope = span.makeCurrent()) {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    sendResponse(exchange, 405, Map.of("error", "Method not allowed"));
                    return;
                }
                EngineModel currentModel = modelManager.getEngineModel();
                DefaultRuleEvaluator evaluator = new DefaultRuleEvaluator(currentModel, tracer, true);

                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Map<String, Object> request = objectMapper.readValue(body, Map.class);
                String eventId = (String) request.getOrDefault("event_id", java.util.UUID.randomUUID().toString());
                span.setAttribute("eventId", eventId);

                Event event = new Event(eventId, (String) request.get("event_type"), (Map<String, Object>) request.get("attributes"));
                MatchResult result = evaluator.evaluate(event);

                span.setAttribute("matchedRuleCount", result.matchedRules().size());
                sendResponse(exchange, 200, result);

            } catch (Exception e) {
                span.recordException(e);
                sendResponse(exchange, 400, Map.of("error", e.getMessage()));
            } finally {
                span.end();
            }
        }
    }

    private void sendResponse(HttpExchange exchange, int statusCode, Object response) throws IOException {
        byte[] bytes = objectMapper.writeValueAsBytes(response);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}