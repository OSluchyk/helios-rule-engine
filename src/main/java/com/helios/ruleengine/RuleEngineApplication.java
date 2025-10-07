package com.helios.ruleengine;

import com.helios.ruleengine.core.compiler.CompilationException;
import com.helios.ruleengine.core.management.EngineModelManager;
import com.helios.ruleengine.infrastructure.telemetry.TracingService;
import com.helios.ruleengine.server.HttpServer;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Level;
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
        modelManager = new EngineModelManager(rulesPath, tracingService.getTracer());
        modelManager.start();

        httpServer = new HttpServer(port, modelManager, tracingService.getTracer());
        httpServer.start();
        logger.info("Rule Engine is ready to serve requests on port " + port);
    }

    private void shutdown() {
        if (modelManager != null) modelManager.shutdown();
        if (httpServer != null) httpServer.stop();
        logger.info("Rule Engine shutdown complete");
    }

    private static void configureLogging() {
        // Logging config...
    }
}