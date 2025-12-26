package com.helios.ruleengine.service.service;

import com.helios.ruleengine.api.model.Event;
import com.helios.ruleengine.api.model.EvaluationResult;
import com.helios.ruleengine.api.model.ExplanationResult;
import com.helios.ruleengine.api.model.MatchResult;
import com.helios.ruleengine.api.model.TraceLevel;
import com.helios.ruleengine.infra.management.EngineModelManager;
import com.helios.ruleengine.runtime.evaluation.RuleEvaluator;
import com.helios.ruleengine.runtime.model.EngineModel;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.Span;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
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
     * Evaluates an event with detailed execution tracing.
     *
     * <p><b>Performance Impact:</b> ~10% overhead. Use for debugging only.
     *
     * @param event the event to evaluate
     * @return evaluation result with trace data (FULL level)
     */
    public EvaluationResult evaluateWithTrace(Event event) {
        EngineModel currentModel = modelManager.getEngineModel();
        RuleEvaluator evaluator = getOrRefreshEvaluator(currentModel);
        return evaluator.evaluateWithTrace(event);
    }

    /**
     * Evaluates an event with configurable trace level.
     *
     * <p><b>Performance Impact:</b>
     * <ul>
     *   <li>BASIC: ~34% overhead - Rule matches only</li>
     *   <li>STANDARD: ~51% overhead - Rule + predicate outcomes</li>
     *   <li>FULL: ~53% overhead - All details + field values</li>
     * </ul>
     *
     * @param event the event to evaluate
     * @param level trace detail level
     * @param conditionalTracing only generate trace if at least one rule matches
     * @return evaluation result with trace data
     */
    public EvaluationResult evaluateWithTrace(Event event, TraceLevel level, boolean conditionalTracing) {
        EngineModel currentModel = modelManager.getEngineModel();
        RuleEvaluator evaluator = getOrRefreshEvaluator(currentModel);
        return evaluator.evaluateWithTrace(event, level, conditionalTracing);
    }

    /**
     * Explains why a specific rule matched or didn't match an event.
     *
     * @param event the event to evaluate
     * @param ruleCode the rule to explain
     * @return explanation result
     */
    public ExplanationResult explainRule(Event event, String ruleCode) {
        EngineModel currentModel = modelManager.getEngineModel();
        RuleEvaluator evaluator = getOrRefreshEvaluator(currentModel);
        return evaluator.explainRule(event, ruleCode);
    }

    /**
     * Evaluates multiple events in batch.
     *
     * @param events list of events to evaluate
     * @return list of match results
     */
    public List<MatchResult> evaluateBatch(List<Event> events) {
        EngineModel currentModel = modelManager.getEngineModel();
        RuleEvaluator evaluator = getOrRefreshEvaluator(currentModel);
        return evaluator.evaluateBatch(events);
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
     * Helper method to get evaluator and refresh if stale.
     *
     * @param currentModel the current engine model
     * @return evaluator instance
     */
    private RuleEvaluator getOrRefreshEvaluator(EngineModel currentModel) {
        RuleEvaluator evaluator = evaluatorPool.get();
        if (evaluator.getModel() != currentModel) {
            Span span = tracer.spanBuilder("evaluator-refresh").startSpan();
            try {
                span.addEvent("Stale model detected. Creating new evaluator.");
                evaluator = createNewEvaluator(currentModel);
                evaluatorPool.set(evaluator);
            } finally {
                span.end();
            }
        }
        return evaluator;
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
