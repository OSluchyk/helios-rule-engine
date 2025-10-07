package com.helios.ruleengine.core.evaluation;

import com.helios.ruleengine.api.IRuleEvaluator;
import com.helios.ruleengine.core.cache.BaseConditionCache;
import com.helios.ruleengine.core.cache.CaffeineBaseConditionCache;
import com.helios.ruleengine.core.model.EngineModel;
import com.helios.ruleengine.infrastructure.telemetry.TracingService;
import com.helios.ruleengine.model.Event;
import com.helios.ruleengine.model.MatchResult;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import org.roaringbitmap.IntIterator;
import org.roaringbitmap.RoaringBitmap;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class RuleEvaluator implements IRuleEvaluator {
    private final EngineModel model;
    private final EvaluatorMetrics metrics;
    private final Tracer tracer;

    private static final ScopedValue<EvaluationContext> CONTEXT = ScopedValue.newInstance();

    private final CachedStaticPredicateEvaluator baseConditionEvaluator;
    private final boolean useBaseConditionCache;
    private final NumericPredicateEvaluator vectorizedEvaluator;

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
        this.vectorizedEvaluator = new NumericPredicateEvaluator(model);

        if (useBaseConditionCache) {
            BaseConditionCache cache = CaffeineBaseConditionCache.builder()
                    .maxSize(100_000)  // Increased from 10k to 100k
                    .expireAfterWrite(10, java.util.concurrent.TimeUnit.MINUTES)
                    .recordStats(true)  // Enable monitoring
                    .initialCapacity(10_000)  // Pre-allocate
                    .build();
            this.baseConditionEvaluator = new CachedStaticPredicateEvaluator(model, cache);
        } else {
            this.baseConditionEvaluator = null;
        }

    }

    public MatchResult evaluate(Event event) {
        int estimatedTouched = Math.min(model.getNumRules() / 10, 1000);
        EvaluationContext freshContext = new EvaluationContext(estimatedTouched);

        return ScopedValue.where(CONTEXT, freshContext)
                .call(() -> {
                    long startTime = System.nanoTime();
                    final EvaluationContext ctx = CONTEXT.get();

                    Span evaluationSpan = tracer.spanBuilder("rule-evaluation").startSpan();
                    try (Scope scope = evaluationSpan.makeCurrent()) {

                        BitSet eligibleRules = null;
                        RoaringBitmap eligibleRulesRoaring = null;

                        if (useBaseConditionCache && baseConditionEvaluator != null) {
                            CompletableFuture<CachedStaticPredicateEvaluator.EvaluationResult> baseFuture =
                                    baseConditionEvaluator.evaluateBaseConditions(event);
                            try {
                                CachedStaticPredicateEvaluator.EvaluationResult baseResult = baseFuture.get();
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

    private void evaluatePredicatesHybrid(Event event, EvaluationContext ctx, BitSet eligibleRules) {
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


    private void updateCountersOptimized(EvaluationContext ctx,
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

    private void detectMatchesOptimized(EvaluationContext ctx, BitSet eligibleRules) {
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
                        EvaluationContext.MutableMatchedRule rule =
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

    private void selectMatches(EvaluationContext ctx) {
        List<EvaluationContext.MutableMatchedRule> matches =
                ctx.getMutableMatchedRules();

        if (matches.size() <= 1) {
            return;
        }

        Map<String, EvaluationContext.MutableMatchedRule> maxPriorityMatches =
                new HashMap<>();

        for (EvaluationContext.MutableMatchedRule match : matches) {
            maxPriorityMatches.merge(match.getRuleCode(), match,
                    (existing, replacement) ->
                            replacement.getPriority() > existing.getPriority() ?
                                    replacement : existing);
        }

        EvaluationContext.MutableMatchedRule overallWinner = null;
        int maxPriority = Integer.MIN_VALUE;

        for (EvaluationContext.MutableMatchedRule rule :
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

}