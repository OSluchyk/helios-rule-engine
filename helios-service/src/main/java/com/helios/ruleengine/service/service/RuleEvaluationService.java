package com.helios.ruleengine.service.service;

import com.helios.ruleengine.api.model.Event;
import com.helios.ruleengine.api.model.MatchResult;
import com.helios.ruleengine.infra.management.EngineModelManager;
import com.helios.ruleengine.runtime.evaluation.RuleEvaluator;
import com.helios.ruleengine.runtime.model.EngineModel;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.Span;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;

/**
 * Service for rule evaluation.
 * Manages thread-local RuleEvaluator instances with hot-reload support.
 */
@ApplicationScoped
public class RuleEvaluationService {

    @Inject
    EngineModelManager modelManager;

    @Inject
    Tracer tracer;

    /**
     * Thread-local pool for RuleEvaluator instances.
     * Ensures each thread has its own evaluator, recreated when model is hot-reloaded.
     */
    private final ThreadLocal<RuleEvaluator> evaluatorPool = ThreadLocal.withInitial(() -> {
        System.out.println("Initializing new RuleEvaluator for thread: " + Thread.currentThread().getName());
        return createNewEvaluator(modelManager.getEngineModel());
    });

    /**
     * Evaluates an event against the current rule engine model.
     *
     * @param event the event to evaluate
     * @return match result containing matched rules
     */
    public MatchResult evaluate(Event event) {
        // Get the most up-to-date model from the manager
        EngineModel currentModel = modelManager.getEngineModel();

        // Get the evaluator from the thread-local pool and check for staleness
        RuleEvaluator evaluator = evaluatorPool.get();
        if (evaluator.getModel() != currentModel) {
            // The model has changed (hot-reload).
            // Create a new evaluator for the new model and update the pool.
            Span span = tracer.spanBuilder("evaluator-refresh").startSpan();
            try {
                span.addEvent("Stale model detected. Creating new evaluator.");
                evaluator = createNewEvaluator(currentModel);
                evaluatorPool.set(evaluator);
            } finally {
                span.end();
            }
        }

        return evaluator.evaluate(event);
    }

    /**
     * Gets detailed metrics from the current thread's evaluator.
     *
     * @return metrics map
     */
    public Map<String, Object> getMetrics() {
        RuleEvaluator evaluator = evaluatorPool.get();
        return evaluator.getDetailedMetrics();
    }

    /**
     * Creates a new RuleEvaluator instance.
     *
     * @param model the engine model
     * @return new evaluator instance
     */
    private RuleEvaluator createNewEvaluator(EngineModel model) {
        // Enable BaseConditionEvaluator cache
        return new RuleEvaluator(model, tracer, true);
    }
}
