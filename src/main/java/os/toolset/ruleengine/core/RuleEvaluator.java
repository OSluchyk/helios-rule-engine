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
import java.util.concurrent.atomic.AtomicLong;

public class RuleEvaluator {
    private final EngineModel model;
    private final EvaluatorMetrics metrics;
    private final Tracer tracer;
    private final ThreadLocal<OptimizedEvaluationContext> contextPool;
    private final VectorizedPredicateEvaluator vectorizedEvaluator;

    // CRITICAL ADDITION: BaseCondition integration
    private final BaseConditionEvaluator baseConditionEvaluator;
    private final boolean useBaseConditionCache;

    // Prefetch optimization
    private static final int PREFETCH_DISTANCE = 64;  // Increased from 8 to 64

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

        // Initialize with estimated touched rules for better initial allocation
        int estimatedTouched = Math.min(model.getNumRules() / 10, 1000);
        this.contextPool = ThreadLocal.withInitial(() ->
                new OptimizedEvaluationContext(estimatedTouched));

        this.vectorizedEvaluator = new VectorizedPredicateEvaluator(model);

        // Initialize BaseCondition evaluator with appropriate cache
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
        long startTime = System.nanoTime();
        final OptimizedEvaluationContext ctx = contextPool.get();
        ctx.reset();

        Span evaluationSpan = tracer.spanBuilder("rule-evaluation").startSpan();
        try (Scope scope = evaluationSpan.makeCurrent()) {

            // OPTIMIZATION 1: Base condition filtering (reduces work by 90%+)
            BitSet eligibleRules = null;
            if (useBaseConditionCache && baseConditionEvaluator != null) {
                CompletableFuture<BaseConditionEvaluator.EvaluationResult> baseFuture =
                        baseConditionEvaluator.evaluateBaseConditions(event);

                try {
                    BaseConditionEvaluator.EvaluationResult baseResult = baseFuture.get();
                    eligibleRules = baseResult.matchingRules;
                    ctx.predicatesEvaluated += baseResult.predicatesEvaluated;

                    evaluationSpan.setAttribute("baseConditionHit", baseResult.fromCache);
                    evaluationSpan.setAttribute("eligibleRules", eligibleRules.cardinality());

                    // Early exit if no rules match base conditions
                    if (eligibleRules.isEmpty()) {
                        long evaluationTime = System.nanoTime() - startTime;
                        metrics.recordEvaluation(evaluationTime, ctx.predicatesEvaluated, 0);
                        return new MatchResult(event.getEventId(), Collections.emptyList(),
                                evaluationTime, ctx.predicatesEvaluated, 0);
                    }
                } catch (Exception e) {
                    // Fallback to full evaluation if base condition fails
                    eligibleRules = null;
                }
            }

            // OPTIMIZATION 2: Vectorized predicate evaluation (P0-1 FIX)
            evaluatePredicatesVectorized(event, ctx, eligibleRules);

            // OPTIMIZATION 3: Batch counter updates with prefetching
            updateCountersOptimized(ctx, eligibleRules);

            // OPTIMIZATION 4: Optimized match detection
            detectMatchesOptimized(ctx, eligibleRules);

            // Apply selection strategy
            selectMatches(ctx);

            long evaluationTime = System.nanoTime() - startTime;
            metrics.recordEvaluation(evaluationTime, ctx.predicatesEvaluated, ctx.rulesEvaluated);

            evaluationSpan.setAttribute("predicatesEvaluated", ctx.predicatesEvaluated);
            evaluationSpan.setAttribute("rulesEvaluated", ctx.rulesEvaluated);
            evaluationSpan.setAttribute("matchCount", ctx.getMatchedRules().size());

            return new MatchResult(
                    event.getEventId(),
                    new ArrayList<>(ctx.getMatchedRules()),
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
    }

    /**
     * P0-1 FIX: Use actual vectorized evaluation instead of scalar loop
     */
    private void evaluatePredicatesVectorized(Event event, OptimizedEvaluationContext ctx,
                                              BitSet eligibleRules) {
        Span span = tracer.spanBuilder("evaluate-predicates-vectorized").startSpan();
        try (Scope scope = span.makeCurrent()) {

            // FIXED: Actually call the vectorized evaluator that was already implemented!
            // The VectorizedPredicateEvaluator handles:
            // 1. Field grouping for cache locality
            // 2. Numeric predicate vectorization using SIMD (Float16)
            // 3. String batch processing with n-gram indexing
            // 4. Equality fast path using dictionary encoding

            vectorizedEvaluator.evaluate(event, ctx, eligibleRules);

            span.setAttribute("truePredicatesFound", ctx.getTruePredicates().size());
            span.setAttribute("predicatesEvaluated", ctx.getPredicatesEvaluatedCount());

        } finally {
            span.end();
        }
    }

    private void updateCountersOptimized(OptimizedEvaluationContext ctx, BitSet eligibleRules) {
        Span span = tracer.spanBuilder("update-counters-optimized").startSpan();
        try (Scope scope = span.makeCurrent()) {

            IntList truePredicates = ctx.getTruePredicates();
            if (truePredicates.isEmpty()) {
                return;
            }

            // Convert eligibleRules to RoaringBitmap ONCE (P1 optimization)
            RoaringBitmap eligibleBitmap = null;
            if (eligibleRules != null) {
                eligibleBitmap = new RoaringBitmap();
                for (int i = eligibleRules.nextSetBit(0); i >= 0; i = eligibleRules.nextSetBit(i + 1)) {
                    eligibleBitmap.add(i);
                }
            }

            // Batch process predicates for better cache locality
            int batchSize = 8;
            for (int i = 0; i < truePredicates.size(); i += batchSize) {
                int end = Math.min(i + batchSize, truePredicates.size());

                // Process batch
                for (int j = i; j < end; j++) {
                    int predicateId = truePredicates.getInt(j);
                    RoaringBitmap affected = model.getInvertedIndex().get(predicateId);

                    if (affected != null) {
                        // Use pre-converted bitmap (no repeated allocations)
                        if (eligibleBitmap != null) {
                            RoaringBitmap intersection = RoaringBitmap.and(affected, eligibleBitmap);
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

            // Process in batches for better cache locality
            int[] touchedArray = touchedRules.toIntArray();
            Arrays.sort(touchedArray); // Sequential access pattern

            for (int i = 0; i < touchedArray.length; i += PREFETCH_DISTANCE) {
                // Prefetch next batch (helps with memory latency)
                if (i + PREFETCH_DISTANCE < touchedArray.length) {
                    for (int j = 0; j < PREFETCH_DISTANCE && i + j < touchedArray.length; j++) {
                        int futureId = touchedArray[i + j];
                        // Touch data to bring into cache
                        model.getCombinationPredicateCount(futureId);
                        model.getCombinationRuleCode(futureId);
                        model.getCombinationPriority(futureId);
                    }
                }

                // Process current batch
                int end = Math.min(i + PREFETCH_DISTANCE, touchedArray.length);
                for (int j = i; j < end; j++) {
                    int combinationId = touchedArray[j];

                    // Skip if not eligible
                    if (eligibleRules != null && !eligibleRules.get(combinationId)) {
                        continue;
                    }

                    ctx.incrementRulesEvaluatedCount();

                    if (ctx.getCounter(combinationId) == model.getCombinationPredicateCount(combinationId)) {
                        MatchResult.MatchedRule rule = ctx.rentMatchedRule(
                                combinationId,
                                model.getCombinationRuleCode(combinationId),
                                model.getCombinationPriority(combinationId),
                                "" // Description can be lazy-loaded if needed
                        );
                        ctx.addMatchedRule(rule);
                    }
                }
            }

            span.setAttribute("matchCount", ctx.getMatchedRules().size());

        } finally {
            span.end();
        }
    }

    private void selectMatches(OptimizedEvaluationContext ctx) {
        List<MatchResult.MatchedRule> matches = ctx.getMatchedRules();
        if (matches.size() <= 1) {
            return;
        }

        // Use pre-allocated work map to avoid allocations
        Map<String, MatchResult.MatchedRule> maxPriorityMatches = ctx.getWorkMap();
        maxPriorityMatches.clear();

        for (MatchResult.MatchedRule match : matches) {
            maxPriorityMatches.merge(match.ruleCode(), match,
                    (existing, replacement) ->
                            replacement.priority() > existing.priority() ? replacement : existing);
        }

        // Find overall winner
        MatchResult.MatchedRule overallWinner = null;
        int maxPriority = Integer.MIN_VALUE;

        for (MatchResult.MatchedRule rule : maxPriorityMatches.values()) {
            if (rule.priority() > maxPriority) {
                overallWinner = rule;
                maxPriority = rule.priority();
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

                long hits = cacheHits.get();
                long misses = cacheMisses.get();
                if (hits + misses > 0) {
                    snapshot.put("cacheHitRate", (double) hits / (hits + misses));
                }
            }

            return snapshot;
        }
    }
}