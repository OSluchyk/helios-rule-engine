package os.toolset.ruleengine.core;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import it.unimi.dsi.fastutil.ints.*;
import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;
import org.roaringbitmap.RoaringBitmap;
import os.toolset.ruleengine.model.Event;
import os.toolset.ruleengine.model.MatchResult;
import os.toolset.ruleengine.model.Predicate;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public class RuleEvaluator {
    private static final ScopedValue<EvaluationContext> CONTEXT = ScopedValue.newInstance();
    private static final VectorSpecies<Double> SPECIES = DoubleVector.SPECIES_PREFERRED;

    private final EngineModel model;
    private final EvaluatorMetrics metrics;
    private final Tracer tracer; // Added for OpenTelemetry

    public RuleEvaluator(EngineModel model) {
        this.model = Objects.requireNonNull(model);
        this.metrics = new EvaluatorMetrics();
        // Get the global tracer instance
        this.tracer = TracingService.getInstance().getTracer();
    }

    public MatchResult evaluate(Event event) {
        long startTime = System.nanoTime();
        Span parentSpan = Span.current(); // Get the parent span from the HTTP server

        return ScopedValue.where(CONTEXT, new EvaluationContext(model.getNumRules()))
                .call(() -> {
                    Span evaluationSpan = tracer.spanBuilder("rule-evaluation").setParent(io.opentelemetry.context.Context.current().with(parentSpan)).startSpan();
                    try (Scope scope = evaluationSpan.makeCurrent()) {

                        evaluatePredicatesVectorized(event);
                        updateCounters();
                        List<MatchResult.MatchedRule> allMatches = detectMatchesSoA();
                        List<MatchResult.MatchedRule> selectedMatches = selectMatches(allMatches);

                        long evaluationTime = System.nanoTime() - startTime;
                        metrics.recordEvaluation(evaluationTime, CONTEXT.get().predicatesEvaluated, CONTEXT.get().rulesEvaluated);

                        evaluationSpan.setAttribute("predicatesEvaluated", CONTEXT.get().predicatesEvaluated);
                        evaluationSpan.setAttribute("uniqueCombinationsConsidered", CONTEXT.get().rulesEvaluated);

                        return new MatchResult(event.getEventId(), selectedMatches, evaluationTime, CONTEXT.get().predicatesEvaluated, CONTEXT.get().rulesEvaluated);

                    } finally {
                        evaluationSpan.end();
                    }
                });
    }

    private void evaluatePredicatesVectorized(Event event) {
        Span span = tracer.spanBuilder("evaluate-predicates").startSpan();
        try (Scope scope = span.makeCurrent()) {
            // (The existing logic for this method remains unchanged)
            final EvaluationContext ctx = CONTEXT.get();
            Int2ObjectMap<Object> encodedEvent = event.getEncodedAttributes(model.getFieldDictionary(), model.getValueDictionary());

            for (Int2ObjectMap.Entry<Object> entry : encodedEvent.int2ObjectEntrySet()) {
                int fieldId = entry.getIntKey();
                Object eventValue = entry.getValue();
                List<Predicate> candidates = model.getFieldToPredicates().get(fieldId);

                if (candidates == null) continue;

                if (eventValue instanceof Number) {
                    evaluateNumericVectorized(fieldId, ((Number) eventValue).doubleValue(), candidates);
                }

                for (Predicate p : candidates) {
                    if (!p.operator().isNumeric()) {
                        ctx.predicatesEvaluated++;
                        if (p.evaluate(eventValue)) {
                            int predicateId = model.getPredicateId(p);
                            if (predicateId != -1) ctx.addTruePredicate(predicateId);
                        }
                    }
                }
            }
            span.setAttribute("truePredicatesFound", ctx.truePredicates.size());
        } finally {
            span.end();
        }
    }

    // (Other methods like updateCounters, detectMatchesSoA also get spans)
    private void updateCounters() {
        Span span = tracer.spanBuilder("update-counters").startSpan();
        try (Scope scope = span.makeCurrent()) {
            final EvaluationContext ctx = CONTEXT.get();
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

    private List<MatchResult.MatchedRule> detectMatchesSoA() {
        Span span = tracer.spanBuilder("detect-matches").startSpan();
        try (Scope scope = span.makeCurrent()) {
            final EvaluationContext ctx = CONTEXT.get();
            List<MatchResult.MatchedRule> matches = new ArrayList<>();
            for (int combinationId : ctx.touchedRules) {
                ctx.rulesEvaluated++;
                if (ctx.counters[combinationId] == model.getCombinationPredicateCount(combinationId)) {
                    matches.add(new MatchResult.MatchedRule(combinationId, model.getCombinationRuleCode(combinationId), model.getCombinationPriority(combinationId), ""));
                }
            }
            span.setAttribute("initialMatchCount", matches.size());
            return matches;
        } finally {
            span.end();
        }
    }

    // The rest of the RuleEvaluator class remains the same...
    private void evaluateNumericVectorized(int fieldId, double eventValue, List<Predicate> candidates) {
        final EvaluationContext ctx = CONTEXT.get();
        double[] predicateValues = new double[SPECIES.length()];
        int[] predicateIds = new int[SPECIES.length()];
        int i = 0;
        for (Predicate p : candidates) {
            if (!p.operator().isNumeric()) continue;
            if (p.operator() == Predicate.Operator.GREATER_THAN || p.operator() == Predicate.Operator.LESS_THAN) {
                predicateValues[i] = ((Number) p.value()).doubleValue();
                predicateIds[i] = model.getPredicateId(p);
                i++;
                if (i == SPECIES.length()) {
                    processNumericVector(eventValue, predicateValues, predicateIds, i);
                    i = 0;
                }
            } else {
                ctx.predicatesEvaluated++;
                if (p.evaluate(eventValue)) {
                    int predId = model.getPredicateId(p);
                    if (predId != -1) ctx.addTruePredicate(predId);
                }
            }
        }
        if (i > 0) {
            processNumericVector(eventValue, predicateValues, predicateIds, i);
        }
    }

    private void processNumericVector(double eventValue, double[] values, int[] ids, int count) {
        final EvaluationContext ctx = CONTEXT.get();
        ctx.predicatesEvaluated += count;
        DoubleVector eventVec = DoubleVector.broadcast(SPECIES, eventValue);
        DoubleVector predVec = DoubleVector.fromArray(SPECIES, values, 0);
        var mask = eventVec.compare(VectorOperators.GT, predVec);
        for (int j = 0; j < count; j++) {
            if (mask.laneIsSet(j)) {
                ctx.addTruePredicate(ids[j]);
            }
        }
    }

    private List<MatchResult.MatchedRule> selectMatches(List<MatchResult.MatchedRule> allMatches) {
        if (allMatches.size() <= 1) return allMatches;
        Map<String, MatchResult.MatchedRule> maxPriorityMatches = new HashMap<>();
        for (MatchResult.MatchedRule match : allMatches) {
            maxPriorityMatches.merge(match.ruleCode(), match, (e, n) -> n.priority() > e.priority() ? n : e);
        }
        ArrayList<MatchResult.MatchedRule> selected = new ArrayList<>(maxPriorityMatches.values());
        selected.sort(Comparator.comparingInt(MatchResult.MatchedRule::priority).reversed());
        return selected;
    }

    public EvaluatorMetrics getMetrics() {
        return metrics;
    }

    private static class EvaluationContext {
        final int[] counters;
        final IntArrayList truePredicates;
        final IntSet touchedRules;
        int predicatesEvaluated;
        int rulesEvaluated;

        EvaluationContext(int maxCombinations) {
            this.counters = new int[maxCombinations];
            this.truePredicates = new IntArrayList();
            this.touchedRules = new IntOpenHashSet();
        }

        void reset() {
            for (int ruleId : touchedRules) counters[ruleId] = 0;
            truePredicates.clear();
            touchedRules.clear();
            predicatesEvaluated = 0;
            rulesEvaluated = 0;
        }

        void addTruePredicate(int predicateId) {
            truePredicates.add(predicateId);
        }

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