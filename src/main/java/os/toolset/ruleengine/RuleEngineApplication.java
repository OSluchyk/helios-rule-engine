package os.toolset.ruleengine;

import os.toolset.ruleengine.core.EngineModelManager;
import os.toolset.ruleengine.core.RuleCompiler;
import os.toolset.ruleengine.server.HttpServer;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.*;

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

    private void start(String[] args) throws IOException, RuleCompiler.CompilationException {
        logger.info("Starting High-Performance Rule Engine with Hot-Reloading");

        String rulesFile = System.getProperty("rules.file", "rules.json");
        int port = Integer.parseInt(System.getProperty("server.port", "8080"));
        Path rulesPath = Paths.get(rulesFile);

        // Initialize and start the model manager
        modelManager = new EngineModelManager(rulesPath);
        modelManager.start();

        // Start the HTTP server with the manager
        httpServer = new HttpServer(port, modelManager);
        httpServer.start();

        logger.info("Rule Engine is ready to serve requests on port " + port);
        printStartupBanner(port);
    }

    private void shutdown() {
        logger.info("Shutting down Rule Engine...");
        if (modelManager != null) {
            modelManager.shutdown();
        }
        if (httpServer != null) {
            httpServer.stop();
        }
        logger.info("Rule Engine shutdown complete");
    }

    private static void configureLogging() {
        LogManager.getLogManager().reset();
        Logger rootLogger = Logger.getLogger("");
        ConsoleHandler consoleHandler = new ConsoleHandler();
        consoleHandler.setLevel(Level.INFO);
        consoleHandler.setFormatter(new SimpleFormatter() {
            @Override
            public String format(LogRecord record) {
                return String.format("[%1$tF %1$tT.%1$tL] [%2$-7s] %3$s - %4$s%n",
                        new java.util.Date(record.getMillis()), record.getLevel(),
                        record.getLoggerName(), record.getMessage());
            }
        });
        rootLogger.addHandler(consoleHandler);
        rootLogger.setLevel(Level.ALL);
    }

    private void printStartupBanner(int port) {
        // Initial model is loaded by the manager's constructor
        int initialRuleCount = modelManager.getEngineModel().getNumRules();
        System.out.printf("""
            ╔═══════════════════════════════════════════════════╗
            ║  HIGH-PERFORMANCE RULE ENGINE - HOT RELOAD ACTIVE ║
            ╠═══════════════════════════════════════════════════╣
            ║   Status: RUNNING                                 ║
            ║   Port: %d                                        ║
            ║   Initial Unique Combinations: %d                 ║
            ╚═══════════════════════════════════════════════════╝
            
            """, port, initialRuleCount);
    }
}