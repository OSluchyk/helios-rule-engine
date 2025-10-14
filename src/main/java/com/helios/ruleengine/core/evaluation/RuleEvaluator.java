package com.helios.ruleengine.core.evaluation;

import com.helios.ruleengine.api.IRuleEvaluator;
import com.helios.ruleengine.core.cache.AdaptiveCaffeineCache;
import com.helios.ruleengine.core.cache.BaseConditionCache;
import com.helios.ruleengine.core.evaluation.context.EvaluationContext;
import com.helios.ruleengine.core.model.EngineModel;
import com.helios.ruleengine.infrastructure.telemetry.TracingService;
import com.helios.ruleengine.model.Event;
import com.helios.ruleengine.model.MatchResult;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import it.unimi.dsi.fastutil.ints.*;
import org.roaringbitmap.RoaringBitmap;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MINIMAL REFACTORING: Keep existing structure, just use PredicateEvaluator facade.
 *
 * CHANGES:
 * - Replace NumericPredicateEvaluator with PredicateEvaluator (if available)
 * - Keep all existing logic intact
 * - Zero API changes
 * - No performance regression
 */
public class RuleEvaluator implements IRuleEvaluator {
    private final EngineModel model;
    private final EvaluatorMetrics metrics;
    private final Tracer tracer;
    private final ThreadLocal<EvaluationContext> contextHolder;

    private final CachedStaticPredicateEvaluator baseConditionEvaluator;
    private final boolean useBaseConditionCache;
    private final NumericPredicateEvaluator vectorizedEvaluator;  // Keep existing name

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
            BaseConditionCache cache = AdaptiveCaffeineCache.builder()
                    .initialMaxSize(100_000)
                    .expireAfterWrite(10, java.util.concurrent.TimeUnit.MINUTES)
                    .recordStats(true)
                    .enableAdaptiveSizing(true)
                    .build();
            this.baseConditionEvaluator = new CachedStaticPredicateEvaluator(model, cache);
        } else {
            this.baseConditionEvaluator = null;
        }

        int estimatedTouched = Math.min(model.getNumRules() / 10, 1000);
        this.contextHolder = ThreadLocal.withInitial(() -> new EvaluationContext(estimatedTouched));
    }

    @Override
    public MatchResult evaluate(Event event) {
        final EvaluationContext ctx = contextHolder.get();
        ctx.reset();

        long startTime = System.nanoTime();
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
            detectMatchesOptimized(ctx, eligibleRulesRoaring);
            selectMatches(ctx);

            List<MatchResult.MatchedRule> matchedRules = new ArrayList<>();
            for (EvaluationContext.MutableMatchedRule mutable : ctx.getMutableMatchedRules()) {
                matchedRules.add(new MatchResult.MatchedRule(
                        mutable.getRuleId(),
                        mutable.getRuleCode(),
                        mutable.getPriority(),
                        mutable.getDescription()
                ));
            }

            long evaluationTime = System.nanoTime() - startTime;
            metrics.recordEvaluation(evaluationTime, ctx.predicatesEvaluated, matchedRules.size());

            evaluationSpan.setAttribute("predicatesEvaluated", ctx.predicatesEvaluated);
            evaluationSpan.setAttribute("rulesMatched", matchedRules.size());
            evaluationSpan.setAttribute("evaluationTimeNanos", evaluationTime);

            return new MatchResult(
                    event.getEventId(),
                    matchedRules,
                    evaluationTime,
                    ctx.predicatesEvaluated,
                    matchedRules.size()
            );

        } catch (Exception e) {
            evaluationSpan.recordException(e);
            throw new RuntimeException("Evaluation failed", e);
        } finally {
            evaluationSpan.end();
        }
    }

    private void evaluatePredicatesHybrid(Event event, EvaluationContext ctx, BitSet eligibleRules) {
        Int2ObjectMap<Object> attributes = event.getEncodedAttributes(
                model.getFieldDictionary(),
                model.getValueDictionary()
        );

        IntSet eligiblePredicateIds = computeEligiblePredicateIds(eligibleRules);

        List<Integer> fieldIds = new ArrayList<>(attributes.keySet());
        fieldIds.sort((a, b) -> Float.compare(
                model.getFieldMinWeight(a),
                model.getFieldMinWeight(b)
        ));

        for (int fieldId : fieldIds) {
            vectorizedEvaluator.evaluateField(fieldId, attributes, ctx, eligiblePredicateIds);
        }
    }

    private IntSet computeEligiblePredicateIds(BitSet eligibleRules) {
        if (eligibleRules == null) {
            return null;
        }

        IntSet cached = eligiblePredicateSetCache.get(eligibleRules);
        if (cached != null) {
            metrics.eligibleSetCacheHits.incrementAndGet();
            return cached;
        }

        IntSet eligible = new IntOpenHashSet();
        for (int ruleId = eligibleRules.nextSetBit(0); ruleId >= 0;
             ruleId = eligibleRules.nextSetBit(ruleId + 1)) {
            IntList predicateIds = model.getCombinationPredicateIds(ruleId);
            eligible.addAll(predicateIds);
        }

        if (eligiblePredicateSetCache.size() < MAX_CACHE_SIZE) {
            eligiblePredicateSetCache.put((BitSet) eligibleRules.clone(), eligible);
        }

        return eligible;
    }

    private void updateCountersOptimized(EvaluationContext ctx, RoaringBitmap eligibleRulesRoaring) {
        for (int predId : ctx.truePredicates) {
            RoaringBitmap affectedRules = model.getInvertedIndex().get(predId);
            if (affectedRules == null) continue;

            if (eligibleRulesRoaring != null) {
                RoaringBitmap intersection = RoaringBitmap.and(affectedRules, eligibleRulesRoaring);
                intersection.forEach((int ruleId) -> {
                    ctx.counters[ruleId]++;
                    ctx.addTouchedRule(ruleId);
                });
            } else {
                affectedRules.forEach((int ruleId) -> {
                    ctx.counters[ruleId]++;
                    ctx.addTouchedRule(ruleId);
                });
            }
        }
    }

    private void detectMatchesOptimized(EvaluationContext ctx, RoaringBitmap eligibleRulesRoaring) {
        IntList touchedRules = ctx.touchedRules;
        int[] counters = ctx.counters;
        int[] needs = model.getPredicateCounts();

        for (int i = 0; i < touchedRules.size(); i++) {
            if (i + PREFETCH_DISTANCE < touchedRules.size()) {
                int prefetchRuleId = touchedRules.getInt(i + PREFETCH_DISTANCE);
                int prefetchNeed = needs[prefetchRuleId];
            }

            int ruleId = touchedRules.getInt(i);
            if (counters[ruleId] >= needs[ruleId]) {
                String ruleCode = model.getCombinationRuleCode(ruleId);
                int priority = model.getCombinationPriority(ruleId);
                String description = "";

                ctx.addMatchedRule(ruleId, ruleCode, priority, description);
            }
        }
    }

    private void selectMatches(EvaluationContext ctx) {
        List<EvaluationContext.MutableMatchedRule> matches = ctx.getMutableMatchedRules();

        if (matches.size() <= 1) {
            return;
        }

        EngineModel.SelectionStrategy strategy = model.getSelectionStrategy();

        switch (strategy) {
            case ALL_MATCHES:
                return;

            case MAX_PRIORITY_PER_FAMILY:
                Map<String, EvaluationContext.MutableMatchedRule> familyWinners = new HashMap<>();

                for (EvaluationContext.MutableMatchedRule match : matches) {
                    familyWinners.merge(match.getRuleCode(), match,
                            (existing, replacement) ->
                                    replacement.getPriority() > existing.getPriority() ?
                                            replacement : existing);
                }

                matches.clear();
                matches.addAll(familyWinners.values());
                break;

            case FIRST_MATCH:
                EvaluationContext.MutableMatchedRule winner = null;
                int maxPriority = Integer.MIN_VALUE;

                for (EvaluationContext.MutableMatchedRule match : matches) {
                    if (match.getPriority() > maxPriority) {
                        winner = match;
                        maxPriority = match.getPriority();
                    }
                }

                matches.clear();
                if (winner != null) {
                    matches.add(winner);
                }
                break;
        }
    }

    public EvaluatorMetrics getMetrics() {
        return metrics;
    }
}