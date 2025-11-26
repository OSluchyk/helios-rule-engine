package com.helios.ruleengine.service;

import com.helios.ruleengine.api.exceptions.CompilationException;
import com.helios.ruleengine.api.IRuleCompiler;
import com.helios.ruleengine.infra.management.EngineModelManager;
import com.helios.ruleengine.service.server.HttpServer;
import com.helios.ruleengine.infra.telemetry.TracingService;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Level;
import java.util.ServiceLoader;

import java.util.logging.Logger;

public class RuleEngineApplication {
    private static final Logger logger = Logger.getLogger(RuleEngineApplication.class.getName());

    private HttpServer httpServer;
    private EngineModelManager modelManager;

    public static void main(String[] args) {
        configureLogging();
        try {
            RuleEngineApplication app = new RuleEngineApplication();
            app.start(args);
            Runtime.getRuntime().addShutdownHook(new Thread(app::shutdown));
            Thread.currentThread().join();
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Application failed to start: " + e.getMessage(), e);
            System.exit(1);
        }
    }

    private void start(String[] args) throws IOException, CompilationException {
        logger.info("Starting High-Performance Rule Engine with OpenTelemetry");
        TracingService tracingService = TracingService.getInstance();
        String rulesFile = System.getProperty("rules.file", "rules.json");
        int port = Integer.parseInt(System.getProperty("server.port", "8080"));
        Path rulesPath = Paths.get(rulesFile);

        // Pass the tracer to the manager
        IRuleCompiler compiler = ServiceLoader.load(IRuleCompiler.class)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No IRuleCompiler implementation found"));

        modelManager = new EngineModelManager(rulesPath, tracingService.getTracer(), compiler);
        modelManager.start();

        httpServer = new HttpServer(port, modelManager, tracingService.getTracer());
        httpServer.start();
        logger.info("Rule Engine is ready to serve requests on port " + port);
    }

    private void shutdown() {
        if (modelManager != null)
            modelManager.shutdown();
        if (httpServer != null)
            httpServer.stop(2 * 60 * 1000);
        logger.info("Rule Engine shutdown complete");
    }

    private static void configureLogging() {
        // Logging config...
    }
}