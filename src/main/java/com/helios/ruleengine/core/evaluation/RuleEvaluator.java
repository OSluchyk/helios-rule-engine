/*
 * Copyright (c) 2025 Helios Rule Engine
 * Licensed under the Apache License, Version 2.0
 */
package com.helios.ruleengine.core.evaluation;

import com.helios.ruleengine.core.cache.AdaptiveCaffeineCache;
import com.helios.ruleengine.core.cache.BaseConditionCache;
import com.helios.ruleengine.core.cache.CacheConfig;
import com.helios.ruleengine.core.cache.CacheFactory;
import com.helios.ruleengine.core.evaluation.cache.BaseConditionEvaluator;
import com.helios.ruleengine.core.evaluation.context.EvaluationContext;
import com.helios.ruleengine.core.evaluation.predicates.PredicateEvaluator;
import com.helios.ruleengine.core.model.EngineModel;
import com.helios.ruleengine.infrastructure.telemetry.TracingService;
import com.helios.ruleengine.model.Event;
import com.helios.ruleengine.model.MatchResult;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import it.unimi.dsi.fastutil.ints.*;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import org.roaringbitmap.RoaringBitmap;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * FIX: Rule evaluator with proper deduplication handling.
 * <p>
 * CRITICAL FIXES:
 * - Deduplicate touched rules to prevent duplicate matches
 * - Handle multiple rule codes per combination (1:N mapping)
 * - Preserve all logical rule associations after IS_ANY_OF expansion
 */
public final class RuleEvaluator {
    private final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(RuleEvaluator.class);

    private static final int PREFETCH_DISTANCE = 64;
    private static final int MAX_CACHE_SIZE = 10_000;

    private final EngineModel model;
    private final Tracer tracer;
    private final EvaluatorMetrics metrics;
    private final PredicateEvaluator predicateEvaluator;
    private final BaseConditionEvaluator baseConditionEvaluator;
    private final Map<BitSet, IntSet> eligiblePredicateSetCache;

    public RuleEvaluator(EngineModel model, Tracer tracer, boolean enableBaseConditionCache) {
        this.model = model;
        this.tracer = tracer;
        this.metrics = new EvaluatorMetrics();
        this.predicateEvaluator = new PredicateEvaluator(model);

        // Create cache and BaseConditionEvaluator if enabled
        if (enableBaseConditionCache) {
            BaseConditionCache cache = CacheFactory.create(CacheConfig.loadDefault());
            this.baseConditionEvaluator = new com.helios.ruleengine.core.evaluation.cache.BaseConditionEvaluator(model, cache);
            logger.info("Cache initialized: {}. rules: {}",
                    cache, model.getNumRules());
        } else {
            this.baseConditionEvaluator = null;
        }

        this.eligiblePredicateSetCache = new HashMap<>();
    }

    public RuleEvaluator(EngineModel model, Tracer tracer) {
        this(model, tracer, false);
    }

    public RuleEvaluator(EngineModel model) {
        this(model, TracingService.getInstance().getTracer(), false);
    }

    public MatchResult evaluate(Event event) {
        Span evaluationSpan = tracer.spanBuilder("evaluate-event").startSpan();
        try (Scope scope = evaluationSpan.makeCurrent()) {
            long startTime = System.nanoTime();

            evaluationSpan.setAttribute("eventId", event.getEventId());
            evaluationSpan.setAttribute("eventType", event.getEventType());

            // Step 1: Initialize evaluation context
            int numRules = model.getNumRules();
            int estimatedTouched = Math.min(numRules / 10, 1000);
            EvaluationContext ctx = new EvaluationContext(numRules, estimatedTouched);

            // Step 1.5: Base condition evaluation (if enabled)
            BitSet eligibleRules = null;
            RoaringBitmap eligibleRulesRoaring = null;

            if (baseConditionEvaluator != null) {
                // FIX: evaluateBaseConditions returns CompletableFuture<EvaluationResult> - takes only Event
                CompletableFuture<BaseConditionEvaluator.EvaluationResult> baseFuture =
                        baseConditionEvaluator.evaluateBaseConditions(event);
                try {
                    BaseConditionEvaluator.EvaluationResult baseResult = baseFuture.get();
                    eligibleRules = baseResult.matchingRules;
                    eligibleRulesRoaring = baseResult.matchingRulesRoaring;

                    ctx.addPredicatesEvaluated(baseResult.predicatesEvaluated);

                    evaluationSpan.setAttribute("baseConditionHit", baseResult.fromCache);
                    evaluationSpan.setAttribute("eligibleRules", eligibleRules.cardinality());

                    if (baseResult.fromCache) {
                        metrics.roaringConversionsSaved.incrementAndGet();
                    }

                    if (eligibleRules.isEmpty()) {
                        long evaluationTime = System.nanoTime() - startTime;
                        metrics.recordEvaluation(evaluationTime, ctx.getPredicatesEvaluated(), 0);
                        return new MatchResult(event.getEventId(), Collections.emptyList(),
                                evaluationTime, ctx.getPredicatesEvaluated(), 0);
                    }
                } catch (Exception e) {
                    evaluationSpan.recordException(e);
                    eligibleRules = null;
                    eligibleRulesRoaring = null;
                }
            } else {
                // No base condition filtering - all rules eligible
                if (model.getNumRules() == 0) {
                    return new MatchResult(event.getEventId(), List.of(), 0, 0, 0);
                }
                eligibleRules = null;
                eligibleRulesRoaring = null;
            }

            // Step 2: Predicate evaluation (FIX: Add child span)
            Span predicateSpan = tracer.spanBuilder("evaluate-predicates").startSpan();
            try (Scope predicateScope = predicateSpan.makeCurrent()) {
                evaluatePredicatesHybrid(event, ctx, eligibleRules);
                predicateSpan.setAttribute("predicatesEvaluated", ctx.getPredicatesEvaluated());
            } finally {
                predicateSpan.end();
            }

            // Step 3: Counter-based matching (FIX: Add child spans)
            Span updateCountersSpan = tracer.spanBuilder("update-counters-optimized").startSpan();
            try (Scope updateScope = updateCountersSpan.makeCurrent()) {
                updateCountersOptimized(ctx, eligibleRulesRoaring);
                updateCountersSpan.setAttribute("touchedRules", ctx.getTouchedRules().size());
            } finally {
                updateCountersSpan.end();
            }

            Span detectMatchesSpan = tracer.spanBuilder("detect-matches-optimized").startSpan();
            try (Scope detectScope = detectMatchesSpan.makeCurrent()) {
                detectMatchesOptimized(ctx, eligibleRulesRoaring);
                detectMatchesSpan.setAttribute("potentialMatches", ctx.getMutableMatchedRules().size());
            } finally {
                detectMatchesSpan.end();
            }

            // Step 4: Rule selection
            selectMatches(ctx);

            // Step 5: Convert mutable matches to immutable result
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
            metrics.recordEvaluation(evaluationTime, ctx.getPredicatesEvaluated(), matchedRules.size());

            evaluationSpan.setAttribute("predicatesEvaluated", ctx.getPredicatesEvaluated());
            evaluationSpan.setAttribute("rulesEvaluated", ctx.getTouchedRules().size());
            evaluationSpan.setAttribute("rulesMatched", matchedRules.size());
            evaluationSpan.setAttribute("evaluationTimeNanos", evaluationTime);

            return new MatchResult(
                    event.getEventId(),
                    matchedRules,
                    evaluationTime,
                    ctx.getPredicatesEvaluated(),
                    matchedRules.size()
            );

        } catch (Exception e) {
            evaluationSpan.recordException(e);
            throw new RuntimeException("Evaluation failed", e);
        } finally {
            evaluationSpan.end();
        }
    }

    private double getCacheHitRate() {
        if (baseConditionEvaluator == null) return 0.0;
        Map<String, Object> metrics = baseConditionEvaluator.getMetrics();
        return (double) metrics.getOrDefault("cacheHitRate", 0.0);
    }

    /**
     * Hybrid predicate evaluation with weight-based ordering.
     * Uses PredicateEvaluator facade for unified evaluation.
     */
    private void evaluatePredicatesHybrid(Event event, EvaluationContext ctx, BitSet eligibleRules) {
        Int2ObjectMap<Object> attributes = event.getEncodedAttributes(
                model.getFieldDictionary(),
                model.getValueDictionary()
        );

        // Compute eligible predicates (cached)
        IntSet eligiblePredicateIds = computeEligiblePredicateIds(eligibleRules);

        // Sort fields by minimum predicate weight (cheap & selective first)
        List<Integer> fieldIds = new ArrayList<>(attributes.keySet());
        fieldIds.sort((a, b) -> Float.compare(
                model.getFieldMinWeight(a),
                model.getFieldMinWeight(b)
        ));

        // Evaluate predicates using unified evaluator
        for (int fieldId : fieldIds) {
            predicateEvaluator.evaluateField(fieldId, attributes, ctx, eligiblePredicateIds);
        }
    }

    /**
     * Compute eligible predicate set with caching (P1-B optimization).
     */
    private IntSet computeEligiblePredicateIds(BitSet eligibleRules) {
        if (eligibleRules == null) {
            return null;
        }

        // Check cache first
        IntSet cached = eligiblePredicateSetCache.get(eligibleRules);
        if (cached != null) {
            metrics.eligibleSetCacheHits.incrementAndGet();
            return cached;
        }

        // Cache miss - compute eligible predicates
        metrics.recordEligibleSetCacheMiss();
        IntSet eligible = new IntOpenHashSet();
        for (int ruleId = eligibleRules.nextSetBit(0); ruleId >= 0;
             ruleId = eligibleRules.nextSetBit(ruleId + 1)) {
            IntList predicateIds = model.getCombinationPredicateIds(ruleId);
            eligible.addAll(predicateIds);
        }

        // Add to cache if space available
        if (eligiblePredicateSetCache.size() < MAX_CACHE_SIZE) {
            eligiblePredicateSetCache.put((BitSet) eligibleRules.clone(), eligible);
        }

        return eligible;
    }

    /**
     * Update rule counters based on true predicates (optimized with RoaringBitmap).
     */
    private void updateCountersOptimized(EvaluationContext ctx, RoaringBitmap eligibleRulesRoaring) {
        IntSet truePredicates = ctx.getTruePredicates();

        for (int predId : truePredicates) {
            RoaringBitmap affectedRules = model.getInvertedIndex().get(predId);
            if (affectedRules == null) continue;

            if (eligibleRulesRoaring != null) {
                // Optimize with eligible rules filter
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

    /**
     * Detect matched rules with prefetching optimization (P1 enhancement).
     * <p>
     * FIX: Deduplicate touched rules and handle multiple rule codes per combination.
     */
    private void detectMatchesOptimized(EvaluationContext ctx, RoaringBitmap eligibleRulesRoaring) {
        IntSet touchedRules = ctx.getTouchedRules();
        int[] counters = ctx.counters;
        int[] needs = model.getPredicateCounts();

        // FIX: Deduplicate touched rules by converting to set
        IntList uniqueTouchedRules = new IntArrayList(touchedRules);

        for (int i = 0; i < uniqueTouchedRules.size(); i++) {
            // Prefetch next cache line (64-byte distance)
            if (i + PREFETCH_DISTANCE < uniqueTouchedRules.size()) {
                int prefetchRuleId = uniqueTouchedRules.getInt(i + PREFETCH_DISTANCE);
                int prefetchNeed = needs[prefetchRuleId];  // Trigger prefetch
            }

            int ruleId = uniqueTouchedRules.getInt(i);
            if (counters[ruleId] >= needs[ruleId]) {
                // Debug: log what's matching
//                System.err.printf("[RuleEvaluator] Match detected: ruleId=%d, counter=%d, needs=%d, predicates=%s%n",
//                        ruleId, counters[ruleId], needs[ruleId], model.getCombinationPredicateIds(ruleId));

                // FIX: Get ALL rule codes associated with this combination
                List<String> ruleCodes = model.getCombinationRuleCodes(ruleId);
                List<Integer> priorities = model.getCombinationPrioritiesAll(ruleId);

                // Add all associated rules
                for (int j = 0; j < ruleCodes.size(); j++) {
                    String ruleCode = ruleCodes.get(j);
                    int priority = priorities.get(j);
                    String description = "";

                    ctx.addMatchedRule(ruleId, ruleCode, priority, description);
                }
            }
        }
    }

    /**
     * Select final matches based on strategy (ALL_MATCHES, MAX_PRIORITY_PER_FAMILY, FIRST_MATCH).
     */
    private void selectMatches(EvaluationContext ctx) {
        List<EvaluationContext.MutableMatchedRule> matches = ctx.getMutableMatchedRules();

        if (matches.size() <= 1) {
            return;
        }

        EngineModel.SelectionStrategy strategy = model.getSelectionStrategy();

        switch (strategy) {
            case ALL_MATCHES:
                // FIX P1: Deduplicate by rule code to avoid duplicates
                // when same rule appears in multiple combinations
                Map<String, EvaluationContext.MutableMatchedRule> uniqueMatches = new LinkedHashMap<>();
                for (EvaluationContext.MutableMatchedRule match : matches) {
                    // Keep first occurrence of each rule code
                    uniqueMatches.putIfAbsent(match.getRuleCode(), match);
                }
                matches.clear();
                matches.addAll(uniqueMatches.values());
                break;

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

    public EngineModel getModel() {
        return model;
    }

    public BaseConditionEvaluator getBaseConditionEvaluator() {
        return baseConditionEvaluator;
    }
}