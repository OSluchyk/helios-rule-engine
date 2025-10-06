package os.toolset.ruleengine.core;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import it.unimi.dsi.fastutil.ints.*;
import org.roaringbitmap.IntIterator;
import org.roaringbitmap.RoaringBitmap;
import os.toolset.ruleengine.core.cache.BaseConditionCache;
import os.toolset.ruleengine.core.cache.InMemoryBaseConditionCache;
import os.toolset.ruleengine.core.evaluation.VectorizedPredicateEvaluator;
import os.toolset.ruleengine.model.Event;
import os.toolset.ruleengine.model.MatchResult;
import os.toolset.ruleengine.model.Predicate;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class RuleEvaluator {
    private final EngineModel model;
    private final EvaluatorMetrics metrics;
    private final Tracer tracer;

    private static final ScopedValue<OptimizedEvaluationContext> CONTEXT = ScopedValue.newInstance();

    private final BaseConditionEvaluator baseConditionEvaluator;
    private final boolean useBaseConditionCache;
    private final VectorizedPredicateEvaluator vectorizedEvaluator;

    private final Map<BitSet, IntSet> eligiblePredicateSetCache;
    private static final int MAX_CACHE_SIZE = 10_000;

    private static final int PREFETCH_DISTANCE = 64;

    public RuleEvaluator(EngineModel model) {
        this(model, TracingService.getInstance().getTracer(), true);
    }

    public RuleEvaluator(EngineModel model, Tracer tracer) {
        this(model, tracer, true);
    }

    public RuleEvaluator(EngineModel model, Tracer tracer, boolean useBaseConditionCache) {
        this.model = Objects.requireNonNull(model);
        this.metrics = new EvaluatorMetrics();
        this.tracer = tracer;
        this.useBaseConditionCache = useBaseConditionCache;
        this.eligiblePredicateSetCache = new ConcurrentHashMap<>(1024);
        this.vectorizedEvaluator = new VectorizedPredicateEvaluator(model);

        if (useBaseConditionCache) {
            BaseConditionCache cache = new InMemoryBaseConditionCache.Builder()
                    .maxSize(10_000)
                    .defaultTtl(5, java.util.concurrent.TimeUnit.MINUTES)
                    .build();
            this.baseConditionEvaluator = new BaseConditionEvaluator(model, cache);
        } else {
            this.baseConditionEvaluator = null;
        }
    }

    public MatchResult evaluate(Event event) {
        int estimatedTouched = Math.min(model.getNumRules() / 10, 1000);
        OptimizedEvaluationContext freshContext = new OptimizedEvaluationContext(estimatedTouched);

        return ScopedValue.where(CONTEXT, freshContext)
                .call(() -> {
                    long startTime = System.nanoTime();
                    final OptimizedEvaluationContext ctx = CONTEXT.get();

                    Span evaluationSpan = tracer.spanBuilder("rule-evaluation").startSpan();
                    try (Scope scope = evaluationSpan.makeCurrent()) {

                        BitSet eligibleRules = null;
                        RoaringBitmap eligibleRulesRoaring = null;

                        if (useBaseConditionCache && baseConditionEvaluator != null) {
                            CompletableFuture<BaseConditionEvaluator.EvaluationResult> baseFuture =
                                    baseConditionEvaluator.evaluateBaseConditions(event);
                            try {
                                BaseConditionEvaluator.EvaluationResult baseResult = baseFuture.get();
                                eligibleRules = baseResult.matchingRules;
                                eligibleRulesRoaring = baseResult.matchingRulesRoaring;
                                ctx.predicatesEvaluated += baseResult.predicatesEvaluated;

                                evaluationSpan.setAttribute("baseConditionHit", baseResult.fromCache);
                                evaluationSpan.setAttribute("eligibleRules", eligibleRules.cardinality());

                                if (baseResult.fromCache) {
                                    metrics.roaringConversionsSaved.incrementAndGet();
                                }

                                if (eligibleRules.isEmpty()) {
                                    long evaluationTime = System.nanoTime() - startTime;
                                    metrics.recordEvaluation(evaluationTime, ctx.predicatesEvaluated, 0);
                                    return new MatchResult(event.getEventId(), Collections.emptyList(),
                                            evaluationTime, ctx.predicatesEvaluated, 0);
                                }
                            } catch (Exception e) {
                                eligibleRules = null;
                                eligibleRulesRoaring = null;
                            }
                        }

                        evaluatePredicatesHybrid(event, ctx, eligibleRules);

                        updateCountersOptimized(ctx, eligibleRulesRoaring);

                        detectMatchesOptimized(ctx, eligibleRules);

                        selectMatches(ctx);

                        long evaluationTime = System.nanoTime() - startTime;
                        metrics.recordEvaluation(evaluationTime, ctx.predicatesEvaluated, ctx.rulesEvaluated);

                        evaluationSpan.setAttribute("predicatesEvaluated", ctx.predicatesEvaluated);
                        evaluationSpan.setAttribute("rulesEvaluated", ctx.rulesEvaluated);
                        evaluationSpan.setAttribute("matchCount", ctx.getMatchedRules().size());

                        return new MatchResult(
                                event.getEventId(),
                                ctx.getMatchedRules(),
                                evaluationTime,
                                ctx.predicatesEvaluated,
                                ctx.rulesEvaluated
                        );

                    } catch (Exception e) {
                        evaluationSpan.recordException(e);
                        throw new RuntimeException("Rule evaluation failed", e);
                    } finally {
                        evaluationSpan.end();
                    }
                });
    }

    private void evaluatePredicatesHybrid(Event event, OptimizedEvaluationContext ctx, BitSet eligibleRules) {
        Span span = tracer.spanBuilder("evaluate-predicates-hybrid").startSpan();
        try (Scope scope = span.makeCurrent()) {
            Int2ObjectMap<Object> attributes = event.getEncodedAttributes(model.getFieldDictionary(), model.getValueDictionary());
            IntSet eligiblePredicateIds = getEligiblePredicateSet(eligibleRules);

            List<Integer> fieldsToEvaluate = new ArrayList<>(attributes.keySet());
            fieldsToEvaluate.sort(Comparator.comparingDouble(model::getFieldMinWeight));

            for (int fieldId : fieldsToEvaluate) {
                vectorizedEvaluator.evaluateField(fieldId, attributes, ctx, eligiblePredicateIds);
            }

            span.setAttribute("predicatesEvaluated", ctx.getPredicatesEvaluatedCount());
            span.setAttribute("truePredicatesFound", ctx.getTruePredicates().size());
        } finally {
            span.end();
        }
    }


    private IntSet getEligiblePredicateSet(BitSet eligibleRules) {
        if (eligibleRules == null) {
            return null;
        }

        IntSet cached = eligiblePredicateSetCache.get(eligibleRules);
        if (cached != null) {
            metrics.eligibleSetCacheHits.incrementAndGet();
            return cached;
        }

        IntSet predicateIds = new IntOpenHashSet();
        for (int ruleId = eligibleRules.nextSetBit(0); ruleId >= 0;
             ruleId = eligibleRules.nextSetBit(ruleId + 1)) {
            IntList rulePreds = model.getCombinationPredicateIds(ruleId);
            for (int predId : rulePreds) {
                predicateIds.add(predId);
            }
        }

        if (eligiblePredicateSetCache.size() < MAX_CACHE_SIZE) {
            eligiblePredicateSetCache.put(eligibleRules, predicateIds);
        }

        return predicateIds;
    }


    private void updateCountersOptimized(OptimizedEvaluationContext ctx,
                                         RoaringBitmap eligibleRulesRoaring) {
        Span span = tracer.spanBuilder("update-counters-optimized").startSpan();
        try (Scope scope = span.makeCurrent()) {

            IntList truePredicates = ctx.getTruePredicates();
            if (truePredicates.isEmpty()) {
                return;
            }

            int batchSize = 8;
            for (int i = 0; i < truePredicates.size(); i += batchSize) {
                int end = Math.min(i + batchSize, truePredicates.size());

                for (int j = i; j < end; j++) {
                    int predicateId = truePredicates.getInt(j);
                    RoaringBitmap affected = model.getInvertedIndex().get(predicateId);

                    if (affected != null) {
                        if (eligibleRulesRoaring != null) {
                            RoaringBitmap intersection = RoaringBitmap.and(
                                    affected, eligibleRulesRoaring);
                            IntIterator it = intersection.getIntIterator();
                            while (it.hasNext()) {
                                ctx.incrementCounter(it.next());
                            }
                        } else {
                            IntIterator it = affected.getIntIterator();
                            while (it.hasNext()) {
                                ctx.incrementCounter(it.next());
                            }
                        }
                    }
                }
            }

            span.setAttribute("countersUpdated", ctx.getTouchedRuleIds().size());

        } finally {
            span.end();
        }
    }

    private void detectMatchesOptimized(OptimizedEvaluationContext ctx, BitSet eligibleRules) {
        Span span = tracer.spanBuilder("detect-matches-optimized").startSpan();
        try (Scope scope = span.makeCurrent()) {

            IntSet touchedRules = ctx.getTouchedRuleIds();

            int[] touchedArray = touchedRules.toIntArray();
            Arrays.sort(touchedArray);

            for (int i = 0; i < touchedArray.length; i += PREFETCH_DISTANCE) {
                if (i + PREFETCH_DISTANCE < touchedArray.length) {
                    for (int j = 0; j < PREFETCH_DISTANCE && i + j < touchedArray.length; j++) {
                        int futureId = touchedArray[i + j];
                        model.getCombinationPredicateCount(futureId);
                        model.getCombinationRuleCode(futureId);
                        model.getCombinationPriority(futureId);
                    }
                }

                int end = Math.min(i + PREFETCH_DISTANCE, touchedArray.length);
                for (int j = i; j < end; j++) {
                    int combinationId = touchedArray[j];

                    if (eligibleRules != null && !eligibleRules.get(combinationId)) {
                        continue;
                    }

                    ctx.incrementRulesEvaluatedCount();

                    if (ctx.getCounter(combinationId) ==
                            model.getCombinationPredicateCount(combinationId)) {
                        OptimizedEvaluationContext.MutableMatchedRule rule =
                                ctx.rentMatchedRule(
                                        combinationId,
                                        model.getCombinationRuleCode(combinationId),
                                        model.getCombinationPriority(combinationId),
                                        ""
                                );
                        ctx.addMatchedRule(rule);
                    }
                }
            }

            span.setAttribute("matchCount", ctx.getMutableMatchedRules().size());

        } finally {
            span.end();
        }
    }

    private void selectMatches(OptimizedEvaluationContext ctx) {
        List<OptimizedEvaluationContext.MutableMatchedRule> matches =
                ctx.getMutableMatchedRules();

        if (matches.size() <= 1) {
            return;
        }

        Map<String, OptimizedEvaluationContext.MutableMatchedRule> maxPriorityMatches =
                new HashMap<>();

        for (OptimizedEvaluationContext.MutableMatchedRule match : matches) {
            maxPriorityMatches.merge(match.getRuleCode(), match,
                    (existing, replacement) ->
                            replacement.getPriority() > existing.getPriority() ?
                                    replacement : existing);
        }

        OptimizedEvaluationContext.MutableMatchedRule overallWinner = null;
        int maxPriority = Integer.MIN_VALUE;

        for (OptimizedEvaluationContext.MutableMatchedRule rule :
                maxPriorityMatches.values()) {
            if (rule.getPriority() > maxPriority) {
                overallWinner = rule;
                maxPriority = rule.getPriority();
            }
        }

        matches.clear();
        if (overallWinner != null) {
            matches.add(overallWinner);
        }
    }

    public EvaluatorMetrics getMetrics() {
        return metrics;
    }

    public static class EvaluatorMetrics {
        private final AtomicLong totalEvaluations = new AtomicLong();
        private final AtomicLong totalTimeNanos = new AtomicLong();
        private final AtomicLong totalPredicatesEvaluated = new AtomicLong();
        private final AtomicLong totalRulesEvaluated = new AtomicLong();
        private final AtomicLong cacheHits = new AtomicLong();
        private final AtomicLong cacheMisses = new AtomicLong();
        final AtomicLong roaringConversionsSaved = new AtomicLong();
        final AtomicLong eligibleSetCacheHits = new AtomicLong();

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
                snapshot.put("avgPredicatesPerEvent", (double) totalPredicatesEvaluated.get() / evals);
                snapshot.put("avgRulesConsideredPerEvent", (double) totalRulesEvaluated.get() / evals);

                long hits = cacheHits.get();
                long misses = cacheMisses.get();
                if (hits + misses > 0) {
                    snapshot.put("cacheHitRate", (double) hits / (hits + misses));
                }

                long conversionsSaved = roaringConversionsSaved.get();
                snapshot.put("roaringConversionsSaved", conversionsSaved);
                snapshot.put("conversionSavingsRate",
                        evals > 0 ? (double) conversionsSaved / evals * 100 : 0.0);

                long eligibleHits = eligibleSetCacheHits.get();
                snapshot.put("eligibleSetCacheHits", eligibleHits);
                snapshot.put("eligibleSetCacheHitRate",
                        evals > 0 ? (double) eligibleHits / evals * 100 : 0.0);
            }

            return snapshot;
        }
    }
}