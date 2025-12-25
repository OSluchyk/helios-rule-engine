package com.helios.ruleengine.service.lifecycle;

import com.helios.ruleengine.infra.management.EngineModelManager;
import com.helios.ruleengine.infra.telemetry.TracingService;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.util.logging.Logger;

/**
 * Application lifecycle management for the rule engine.
 * Handles startup and shutdown events using Quarkus lifecycle callbacks.
 */
@ApplicationScoped
public class RuleEngineLifecycle {

    private static final Logger logger = Logger.getLogger(RuleEngineLifecycle.class.getName());

    @Inject
    EngineModelManager modelManager;

    @Inject
    TracingService tracingService;

    /**
     * Called when the application starts.
     * Initializes the model manager's file watcher.
     */
    void onStart(@Observes StartupEvent event) {
        logger.info("Starting Helios Rule Engine with Quarkus");
        modelManager.start();
        logger.info("Rule Engine is ready to serve requests");
    }

    /**
     * Called when the application shuts down.
     * Performs graceful cleanup of resources.
     */
    void onStop(@Observes ShutdownEvent event) {
        logger.info("Shutting down Rule Engine");
        modelManager.shutdown();
        tracingService.shutdown();
        logger.info("Rule Engine shutdown complete");
    }
}
