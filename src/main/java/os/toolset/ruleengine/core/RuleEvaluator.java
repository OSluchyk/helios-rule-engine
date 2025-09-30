package os.toolset.ruleengine.core;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import it.unimi.dsi.fastutil.ints.*;
import org.roaringbitmap.IntIterator;
import org.roaringbitmap.RoaringBitmap;
import os.toolset.ruleengine.core.evaluation.VectorizedPredicateEvaluator;
import os.toolset.ruleengine.model.Event;
import os.toolset.ruleengine.model.MatchResult;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public class RuleEvaluator {

    private final EngineModel model;
    private final EvaluatorMetrics metrics;
    private final Tracer tracer;
    private final ThreadLocal<OptimizedEvaluationContext> contextPool;
    private final VectorizedPredicateEvaluator vectorizedEvaluator;

    public RuleEvaluator(EngineModel model) {
        this.model = Objects.requireNonNull(model);
        this.metrics = new EvaluatorMetrics();
        this.tracer = TracingService.getInstance().getTracer();
        this.contextPool = ThreadLocal.withInitial(() -> new OptimizedEvaluationContext(model.getNumRules()));
        this.vectorizedEvaluator = new VectorizedPredicateEvaluator(model);
    }

    public MatchResult evaluate(Event event) {
        long startTime = System.nanoTime();
        final OptimizedEvaluationContext ctx = contextPool.get();
        ctx.reset();

        Span parentSpan = Span.current();
        Span evaluationSpan = tracer.spanBuilder("rule-evaluation").setParent(io.opentelemetry.context.Context.current().with(parentSpan)).startSpan();
        try (Scope scope = evaluationSpan.makeCurrent()) {

            evaluatePredicates(event, ctx);
            updateCounters(ctx);
            detectMatches(ctx);
            List<MatchResult.MatchedRule> selectedMatches = selectMatches(ctx);

            long evaluationTime = System.nanoTime() - startTime;
            metrics.recordEvaluation(evaluationTime, ctx.getPredicatesEvaluatedCount(), ctx.getRulesEvaluatedCount());

            evaluationSpan.setAttribute("predicatesEvaluated", ctx.getPredicatesEvaluatedCount());
            evaluationSpan.setAttribute("uniqueCombinationsConsidered", ctx.getRulesEvaluatedCount());

            return new MatchResult(event.getEventId(), new ArrayList<>(selectedMatches), evaluationTime, ctx.getPredicatesEvaluatedCount(), ctx.getRulesEvaluatedCount());

        } finally {
            evaluationSpan.end();
        }
    }

    private void evaluatePredicates(Event event, OptimizedEvaluationContext ctx) {
        Span span = tracer.spanBuilder("evaluate-predicates").startSpan();
        try (Scope scope = span.makeCurrent()) {
            vectorizedEvaluator.evaluate(event, ctx);
            span.setAttribute("truePredicatesFound", ctx.getTruePredicates().size());
        } finally {
            span.end();
        }
    }

    private void updateCounters(OptimizedEvaluationContext ctx) {
        Span span = tracer.spanBuilder("update-counters").startSpan();
        try (Scope scope = span.makeCurrent()) {
            IntList truePredicates = ctx.getTruePredicates();
            for (int i = 0; i < truePredicates.size(); i++) {
                int predicateId = truePredicates.getInt(i);
                RoaringBitmap affected = model.getInvertedIndex().get(predicateId);
                if (affected != null) {
                    IntIterator it = affected.getIntIterator();
                    while (it.hasNext()) {
                        ctx.incrementCounter(it.next());
                    }
                }
            }
        } finally {
            span.end();
        }
    }

    private void detectMatches(OptimizedEvaluationContext ctx) {
        Span span = tracer.spanBuilder("detect-matches").startSpan();
        try (Scope scope = span.makeCurrent()) {
            IntSet touchedRules = ctx.getTouchedRuleIds();
            touchedRules.forEach((int combinationId) -> {
                ctx.incrementRulesEvaluatedCount();
                if (ctx.getCounter(combinationId) == model.getCombinationPredicateCount(combinationId)) {
                    ctx.addMatchedRule(new MatchResult.MatchedRule(combinationId, model.getCombinationRuleCode(combinationId), model.getCombinationPriority(combinationId), ""));
                }
            });
            span.setAttribute("initialMatchCount", ctx.getMatchedRules().size());
        } finally {
            span.end();
        }
    }

    private List<MatchResult.MatchedRule> selectMatches(OptimizedEvaluationContext ctx) {
        if (ctx.getMatchedRules().isEmpty()) {
            return Collections.emptyList();
        }

        // **FIXED LOGIC**: Find the single highest priority rule among all matches.
        return ctx.getMatchedRules().stream()
                .max(Comparator.comparingInt(MatchResult.MatchedRule::priority))
                .map(List::of)
                .orElse(Collections.emptyList());
    }

    public EvaluatorMetrics getMetrics() { return metrics; }


    public static class EvaluatorMetrics {
        private final AtomicLong totalEvaluations = new AtomicLong();
        private final AtomicLong totalTimeNanos = new AtomicLong();
        private final AtomicLong totalPredicatesEvaluated = new AtomicLong();
        private final AtomicLong totalRulesEvaluated = new AtomicLong();

        void recordEvaluation(long timeNanos, int predicatesEvaluated, int rulesEvaluated) {
            totalEvaluations.incrementAndGet();
            totalTimeNanos.addAndGet(timeNanos);
            totalPredicatesEvaluated.addAndGet(predicatesEvaluated);
            totalRulesEvaluated.addAndGet(rulesEvaluated);
        }

        public Map<String, Object> getSnapshot() {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            long evals = totalEvaluations.get();
            if (evals > 0) {
                snapshot.put("totalEvaluations", evals);
                snapshot.put("avgLatencyMicros", totalTimeNanos.get() / 1000 / evals);
                snapshot.put("avgPredicatesPerEvent", totalPredicatesEvaluated.get() / evals);
                snapshot.put("avgRulesConsideredPerEvent", totalRulesEvaluated.get() / evals);
            }
            return snapshot;
        }
    }
}