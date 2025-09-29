// File: src/main/java/com/google/ruleengine/RuleEngineApplication.java
package os.toolset.ruleengine;



import os.toolset.ruleengine.core.EngineModel;
import os.toolset.ruleengine.core.RuleCompiler;
import os.toolset.ruleengine.core.RuleEvaluator;
import os.toolset.ruleengine.server.HttpServer;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.*;

/**
 * Main application class for the High-Performance Rule Engine.
 * Implements startup, shutdown hooks, and configuration.
 */
public class RuleEngineApplication {
    private static final Logger logger = Logger.getLogger(RuleEngineApplication.class.getName());

    private os.toolset.ruleengine.server.HttpServer httpServer;
    private RuleEvaluator evaluator;
    private EngineModel model;

    public static void main(String[] args) {
        configureLogging();

        try {
            RuleEngineApplication app = new RuleEngineApplication();
            app.start(args);

            // Add shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(app::shutdown));

            // Keep main thread alive
            Thread.currentThread().join();

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Application failed to start: " + e.getMessage(), e);
            System.exit(1);
        }
    }

    private void start(String[] args) throws IOException, RuleCompiler.CompilationException {
        logger.info("Starting High-Performance Rule Engine - Phase 3");

        // Parse configuration
        String rulesFile = System.getProperty("rules.file", "rules.json");
        int port = Integer.parseInt(System.getProperty("server.port", "8080"));

        Path rulesPath = Paths.get(rulesFile);

        // Compile rules
        logger.info("Compiling rules from: " + rulesPath);
        RuleCompiler compiler = new RuleCompiler();
        model = compiler.compile(rulesPath);

        // Initialize evaluator
        evaluator = new RuleEvaluator(model);

        // Start HTTP server
        httpServer = new os.toolset.ruleengine.server.HttpServer(port, evaluator, model);
        httpServer.start();

        logger.info("Rule Engine is ready to serve requests on port " + port);
        printStartupBanner(port);
    }

    private void shutdown() {
        logger.info("Shutting down Rule Engine...");

        if (httpServer != null) {
            httpServer.stop();
        }

        // Print final metrics
        if (evaluator != null) {
            logger.info("Final metrics: " + evaluator.getMetrics().getSnapshot());
        }

        logger.info("Rule Engine shutdown complete");
    }

    private static void configureLogging() {
        LogManager.getLogManager().reset();
        Logger rootLogger = Logger.getLogger("");

        ConsoleHandler consoleHandler = new ConsoleHandler();
        consoleHandler.setLevel(Level.INFO); // Set to FINE or FINER for debug logs
        consoleHandler.setFormatter(new SimpleFormatter() {
            @Override
            public String format(LogRecord record) {
                return String.format("[%1$tF %1$tT.%1$tL] [%2$-7s] %3$s - %4$s%n",
                        new java.util.Date(record.getMillis()),
                        record.getLevel(),
                        record.getLoggerName(),
                        record.getMessage());
            }
        });

        rootLogger.addHandler(consoleHandler);
        rootLogger.setLevel(Level.ALL); // Capture all levels
    }

    private void printStartupBanner(int port) {
        System.out.println("""
            ╔═══════════════════════════════════════════════════╗
            ║  HIGH-PERFORMANCE RULE ENGINE - PHASE 3          ║
            ║  Advanced Operators & Selection Logic Active      ║
            ╠═══════════════════════════════════════════════════╣
            ║   Status: RUNNING                                 ║
            ║   Port: %d                                      ║
            ║   Logical Rules: %.0f                             ║
            ║   Internal Rules: %d                             ║
            ║   Predicates: %d                                 ║
            ╚═══════════════════════════════════════════════════╝
            
            Endpoints:
              POST /evaluate - Evaluate an event
              GET  /health   - Health status
              GET  /metrics  - Performance metrics
              GET  /rules    - List loaded rules
            """.formatted(port,
                (double)model.getStats().totalRules() / (double)model.getStats().metadata().getOrDefault("expansionFactor", 1.0),
                model.getStats().totalRules(),
                model.getStats().totalPredicates()));
    }
}
