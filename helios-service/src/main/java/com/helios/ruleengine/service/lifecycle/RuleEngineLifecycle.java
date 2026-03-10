package com.helios.ruleengine.service.lifecycle;

import com.helios.ruleengine.api.model.RuleDefinition;
import com.helios.ruleengine.api.model.RuleMetadata;
import com.helios.ruleengine.infra.management.EngineModelManager;
import com.helios.ruleengine.infra.telemetry.TracingService;
import com.helios.ruleengine.service.monitoring.RuleMetricsAggregator;
import com.helios.ruleengine.service.repository.JdbcRuleRepository;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Application lifecycle management for the rule engine.
 * Handles startup and shutdown events using Quarkus lifecycle callbacks.
 *
 * On startup, loads rules from the database (the sole source of truth)
 * and compiles them into the active engine model.
 */
@ApplicationScoped
public class RuleEngineLifecycle {

    private static final Logger logger = Logger.getLogger(RuleEngineLifecycle.class.getName());

    @Inject
    EngineModelManager modelManager;

    @Inject
    TracingService tracingService;

    @Inject
    JdbcRuleRepository ruleRepository;

    @Inject
    RuleMetricsAggregator metricsAggregator;

    /**
     * Called when the application starts.
     * Loads rules from the database and compiles the engine model.
     */
    void onStart(@Observes StartupEvent event) {
        logger.info("Starting Helios Rule Engine with Quarkus");
        modelManager.start();
        loadRulesFromDatabase();
        metricsAggregator.startThroughputSampler();
        logger.info("Rule Engine is ready to serve requests");
    }

    private void loadRulesFromDatabase() {
        try {
            List<RuleMetadata> allRules = ruleRepository.findAll();

            if (allRules.isEmpty()) {
                logger.info("No rules found in database. Starting with empty model. " +
                           "Import rules via the API to begin.");
                return;
            }

            List<RuleMetadata> enabledRules = allRules.stream()
                    .filter(r -> r.enabled() != null && r.enabled())
                    .toList();

            if (enabledRules.isEmpty()) {
                logger.warning("Found " + allRules.size() + " rules in database, " +
                              "but none are enabled. Starting with empty model.");
                return;
            }

            List<RuleDefinition> definitions = enabledRules.stream()
                    .map(this::toRuleDefinition)
                    .toList();

            modelManager.compileFromRules(definitions, null);
            logger.info("Loaded and compiled " + enabledRules.size() + " rules from database " +
                        "(" + allRules.size() + " total, " + (allRules.size() - enabledRules.size()) + " disabled)");

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to load rules from database on startup. " +
                       "Engine will start with empty model.", e);
        }
    }

    private RuleDefinition toRuleDefinition(RuleMetadata metadata) {
        return new RuleDefinition(
                metadata.ruleCode(),
                metadata.conditions(),
                metadata.priority() != null ? metadata.priority() : 0,
                metadata.description(),
                metadata.enabled() != null ? metadata.enabled() : true
        );
    }

    /**
     * Called when the application shuts down.
     * Performs graceful cleanup of resources.
     */
    void onStop(@Observes ShutdownEvent event) {
        logger.info("Shutting down Rule Engine");
        metricsAggregator.shutdownThroughputSampler();
        modelManager.shutdown();
        tracingService.shutdown();
        logger.info("Rule Engine shutdown complete");
    }
}
