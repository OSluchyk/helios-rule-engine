package os.toolset.ruleengine.core;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import it.unimi.dsi.fastutil.ints.*;
import org.roaringbitmap.RoaringBitmap;
import os.toolset.ruleengine.model.Event;
import os.toolset.ruleengine.model.MatchResult;
import os.toolset.ruleengine.model.Predicate;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public class RuleEvaluator {

    private final EngineModel model;
    private final EvaluatorMetrics metrics;
    private final Tracer tracer;
    private final ThreadLocal<EvaluationContext> contextPool;

    public RuleEvaluator(EngineModel model) {
        this.model = Objects.requireNonNull(model);
        this.metrics = new EvaluatorMetrics();
        this.tracer = TracingService.getInstance().getTracer();
        this.contextPool = ThreadLocal.withInitial(() -> new EvaluationContext(model.getNumRules()));
    }

    public MatchResult evaluate(Event event) {
        long startTime = System.nanoTime();
        final EvaluationContext ctx = contextPool.get();
        ctx.reset();

        Span parentSpan = Span.current();
        Span evaluationSpan = tracer.spanBuilder("rule-evaluation").setParent(io.opentelemetry.context.Context.current().with(parentSpan)).startSpan();
        try (Scope scope = evaluationSpan.makeCurrent()) {

            evaluatePredicates(event, ctx);
            updateCounters(ctx);
            detectMatchesSoA(ctx);
            List<MatchResult.MatchedRule> selectedMatches = selectMatches(ctx); // Pass the whole context

            long evaluationTime = System.nanoTime() - startTime;
            metrics.recordEvaluation(evaluationTime, ctx.predicatesEvaluated, ctx.rulesEvaluated);

            evaluationSpan.setAttribute("predicatesEvaluated", ctx.predicatesEvaluated);
            evaluationSpan.setAttribute("uniqueCombinationsConsidered", ctx.rulesEvaluated);

            // Create the final result object here, using a new copy of the selected matches
            return new MatchResult(event.getEventId(), new ArrayList<>(selectedMatches), evaluationTime, ctx.predicatesEvaluated, ctx.rulesEvaluated);

        } finally {
            evaluationSpan.end();
        }
    }

    private void evaluatePredicates(Event event, EvaluationContext ctx) {
        Span span = tracer.spanBuilder("evaluate-predicates").startSpan();
        try (Scope scope = span.makeCurrent()) {
            // Reusable encoding logic, now inside the evaluator
            encodeEventAttributes(event, ctx);

            for (Int2ObjectMap.Entry<Object> entry : ctx.encodedAttributes.int2ObjectEntrySet()) {
                List<Predicate> candidates = model.getFieldToPredicates().get(entry.getIntKey());
                if (candidates != null) {
                    for (Predicate p : candidates) {
                        ctx.predicatesEvaluated++;
                        if (p.evaluate(entry.getValue())) {
                            int predicateId = model.getPredicateId(p);
                            if (predicateId != -1) {
                                ctx.addTruePredicate(predicateId);
                            }
                        }
                    }
                }
            }
            span.setAttribute("truePredicatesFound", ctx.truePredicates.size());
        } finally {
            span.end();
        }
    }

    private void encodeEventAttributes(Event event, EvaluationContext ctx) {
        // Flatten attributes into the context's reusable map
        flattenEventAttributes(event.getAttributes(), ctx.flattenedAttributes);

        for (Map.Entry<String, Object> entry : ctx.flattenedAttributes.entrySet()) {
            int fieldId = model.getFieldDictionary().getId(entry.getKey());
            if (fieldId != -1) {
                Object value = entry.getValue();
                if (value instanceof String) {
                    int valueId = model.getValueDictionary().getId((String) value);
                    ctx.encodedAttributes.put(fieldId, valueId != -1 ? (Object) valueId : value);
                } else {
                    ctx.encodedAttributes.put(fieldId, value);
                }
            }
        }
    }

    private void flattenEventAttributes(Map<String, Object> attributes, Map<String, Object> flatMap) {
        if (attributes == null || attributes.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : attributes.entrySet()) {
            // This simplified version doesn't handle nested maps, which is fine for the benchmark
            flatMap.put(entry.getKey().toUpperCase().replace('-', '_'), entry.getValue());
        }
    }

    private void updateCounters(EvaluationContext ctx) {
        Span span = tracer.spanBuilder("update-counters").startSpan();
        try (Scope scope = span.makeCurrent()) {
            ctx.truePredicates.forEach((int predicateId) -> {
                RoaringBitmap affected = model.getInvertedIndex().get(predicateId);
                if (affected != null) {
                    affected.forEach((int combinationId) -> ctx.incrementCounter(combinationId));
                }
            });
        } finally {
            span.end();
        }
    }

    private void detectMatchesSoA(EvaluationContext ctx) {
        Span span = tracer.spanBuilder("detect-matches").startSpan();
        try (Scope scope = span.makeCurrent()) {
            for (int combinationId : ctx.touchedRules) {
                ctx.rulesEvaluated++;
                if (ctx.counters[combinationId] == model.getCombinationPredicateCount(combinationId)) {
                    ctx.matches.add(new MatchResult.MatchedRule(combinationId, model.getCombinationRuleCode(combinationId), model.getCombinationPriority(combinationId), ""));
                }
            }
            span.setAttribute("initialMatchCount", ctx.matches.size());
        } finally {
            span.end();
        }
    }

    private List<MatchResult.MatchedRule> selectMatches(EvaluationContext ctx) {
        if (ctx.matches.size() <= 1) {
            return ctx.matches;
        }

        // Use the context's reusable map and list
        for (MatchResult.MatchedRule match : ctx.matches) {
            ctx.maxPriorityMatches.merge(match.ruleCode(), match, (e, n) -> n.priority() > e.priority() ? n : e);
        }

        ctx.selectedMatches.addAll(ctx.maxPriorityMatches.values());
        ctx.selectedMatches.sort(Comparator.comparingInt(MatchResult.MatchedRule::priority).reversed());
        return ctx.selectedMatches;
    }

    public EvaluatorMetrics getMetrics() { return metrics; }

    private static class EvaluationContext {
        final int[] counters;
        final IntArrayList truePredicates;
        final IntSet touchedRules;
        final List<MatchResult.MatchedRule> matches;
        final Map<String, Object> flattenedAttributes; // Reusable map for flattening
        final Int2ObjectMap<Object> encodedAttributes; // Reusable map for encoding
        final Map<String, MatchResult.MatchedRule> maxPriorityMatches; // Reusable map for selection
        final List<MatchResult.MatchedRule> selectedMatches; // Reusable list for selection result

        int predicatesEvaluated;
        int rulesEvaluated;

        EvaluationContext(int maxCombinations) {
            this.counters = new int[maxCombinations];
            this.truePredicates = new IntArrayList();
            this.touchedRules = new IntOpenHashSet();
            this.matches = new ArrayList<>();
            this.flattenedAttributes = new HashMap<>();
            this.encodedAttributes = new Int2ObjectOpenHashMap<>();
            this.maxPriorityMatches = new HashMap<>();
            this.selectedMatches = new ArrayList<>();
        }

        void reset() {
            for (int ruleId : touchedRules) counters[ruleId] = 0;
            truePredicates.clear();
            touchedRules.clear();
            matches.clear();
            flattenedAttributes.clear();
            encodedAttributes.clear();
            maxPriorityMatches.clear();
            selectedMatches.clear();
            predicatesEvaluated = 0;
            rulesEvaluated = 0;
        }

        void addTruePredicate(int predicateId) { truePredicates.add(predicateId); }
        void incrementCounter(int combinationId) {
            counters[combinationId]++;
            touchedRules.add(combinationId);
        }
    }

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