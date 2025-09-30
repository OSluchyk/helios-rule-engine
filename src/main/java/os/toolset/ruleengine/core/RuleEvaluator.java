package os.toolset.ruleengine.core;

import it.unimi.dsi.fastutil.ints.*;
import org.roaringbitmap.IntIterator;
import org.roaringbitmap.RoaringBitmap;
import os.toolset.ruleengine.core.cache.BaseConditionCache;
import os.toolset.ruleengine.core.cache.InMemoryBaseConditionCache;
import os.toolset.ruleengine.model.Event;
import os.toolset.ruleengine.model.MatchResult;
import os.toolset.ruleengine.model.Predicate;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Finalized Rule Evaluation Engine with integrated Base Condition Caching.
 */
public class RuleEvaluator {
    private static final Logger logger = Logger.getLogger(RuleEvaluator.class.getName());

    private final EngineModel model;
    private final EvaluatorMetrics metrics;
    private final ThreadLocal<EvaluationContext> contextPool;

    // Base condition caching components
    private final BaseConditionCache cache;
    private final BaseConditionEvaluator baseEvaluator;
    private final boolean cacheEnabled;

    public RuleEvaluator(EngineModel model) {
        this(model, createDefaultCache(), true);
    }

    public RuleEvaluator(EngineModel model, BaseConditionCache cache, boolean enableCache) {
        this.model = Objects.requireNonNull(model);
        this.metrics = new EvaluatorMetrics();
        this.contextPool = ThreadLocal.withInitial(() -> new EvaluationContext(model.getNumRules()));
        this.cache = cache;
        this.cacheEnabled = enableCache && cache != null;

        if (this.cacheEnabled) {
            this.baseEvaluator = new BaseConditionEvaluator(model, cache);
            logger.info("RuleEvaluator initialized with Base Condition Caching ENABLED.");
        } else {
            this.baseEvaluator = null;
            logger.info("RuleEvaluator initialized with Base Condition Caching DISABLED.");
        }
    }

    private static BaseConditionCache createDefaultCache() {
        return new InMemoryBaseConditionCache.Builder()
                .maxSize(10_000)
                .defaultTtl(5, TimeUnit.MINUTES)
                .build();
    }

    public MatchResult evaluate(Event event) {
        if (cacheEnabled) {
            try {
                // Default path is now async with caching, blocking for the result.
                return evaluateAsync(event).get();
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Error during async evaluation, falling back to sync", e);
                return evaluateSync(event); // Fallback to non-cached version on error
            }
        }
        return evaluateSync(event);
    }

    public CompletableFuture<MatchResult> evaluateAsync(Event event) {
        long startTime = System.nanoTime();
        EvaluationContext ctx = contextPool.get();
        ctx.reset();

        return baseEvaluator.evaluateBaseConditions(event)
                .thenApply(baseResult -> {
                    ctx.predicatesEvaluated += baseResult.predicatesEvaluated;
                    BitSet eligibleCombinations = baseResult.matchingRules;

                    evaluateRemainingPredicates(event, ctx, eligibleCombinations);
                    updateCountersFiltered(ctx, eligibleCombinations);

                    List<MatchResult.MatchedRule> allMatches = detectMatchesSoA(ctx);
                    List<MatchResult.MatchedRule> selectedMatches = selectMatches(allMatches);

                    long evaluationTime = System.nanoTime() - startTime;
                    metrics.recordEvaluation(evaluationTime, ctx.predicatesEvaluated, ctx.rulesEvaluated);

                    return new MatchResult(
                            event.getEventId(),
                            selectedMatches,
                            evaluationTime,
                            ctx.predicatesEvaluated,
                            ctx.rulesEvaluated
                    );
                });
    }

    private MatchResult evaluateSync(Event event) {
        long startTime = System.nanoTime();
        EvaluationContext ctx = contextPool.get();
        ctx.reset();

        evaluateAllPredicates(event, ctx);
        updateAllCounters(ctx);
        List<MatchResult.MatchedRule> allMatches = detectMatchesSoA(ctx);
        List<MatchResult.MatchedRule> selectedMatches = selectMatches(allMatches);

        long evaluationTime = System.nanoTime() - startTime;
        metrics.recordEvaluation(evaluationTime, ctx.predicatesEvaluated, ctx.rulesEvaluated);

        return new MatchResult(event.getEventId(), selectedMatches, evaluationTime, ctx.predicatesEvaluated, ctx.rulesEvaluated);
    }


    private void evaluateRemainingPredicates(Event event, EvaluationContext ctx, BitSet eligibleCombinations) {
        Int2ObjectMap<Object> encodedEvent = event.getEncodedAttributes(model.getFieldDictionary(), model.getValueDictionary());
        IntSet evaluatedPredicates = new IntOpenHashSet();

        for (int combinationId = eligibleCombinations.nextSetBit(0); combinationId >= 0; combinationId = eligibleCombinations.nextSetBit(combinationId + 1)) {
            IntList predicateIds = model.getCombinationPredicateIds(combinationId);
            for (int predId : predicateIds) {
                if (evaluatedPredicates.add(predId)) { // Evaluate each predicate only once
                    Predicate p = model.getPredicate(predId);
                    if (!baseEvaluator.isStaticPredicate(p)) {
                        ctx.predicatesEvaluated++;
                        if (p.evaluate(encodedEvent.get(p.fieldId()))) {
                            ctx.addTruePredicate(predId);
                        }
                    } else {
                        // Base predicates are true if we're here, so just add them
                        ctx.addTruePredicate(predId);
                    }
                }
            }
        }
    }

    private void evaluateAllPredicates(Event event, EvaluationContext ctx) {
        Int2ObjectMap<Object> encodedEvent = event.getEncodedAttributes(model.getFieldDictionary(), model.getValueDictionary());
        for (Int2ObjectMap.Entry<Object> entry : encodedEvent.int2ObjectEntrySet()) {
            List<Predicate> candidates = model.getFieldToPredicates().get(entry.getIntKey());
            if (candidates != null) {
                for (Predicate p : candidates) {
                    ctx.predicatesEvaluated++;
                    if (p.evaluate(entry.getValue())) {
                        int predicateId = model.getPredicateId(p);
                        if (predicateId != -1) ctx.addTruePredicate(predicateId);
                    }
                }
            }
        }
    }

    private void updateCountersFiltered(EvaluationContext ctx, BitSet eligibleCombinations) {
        ctx.truePredicates.forEach((int predicateId) -> {
            RoaringBitmap affected = model.getInvertedIndex().get(predicateId);
            if (affected != null) {
                IntIterator it = affected.getIntIterator();
                while (it.hasNext()) {
                    int combinationId = it.next();
                    if (eligibleCombinations.get(combinationId)) {
                        ctx.incrementCounter(combinationId);
                    }
                }
            }
        });
    }

    private void updateAllCounters(EvaluationContext ctx) {
        ctx.truePredicates.forEach((int predicateId) -> {
            RoaringBitmap affected = model.getInvertedIndex().get(predicateId);
            if (affected != null) {
                affected.forEach((int combinationId) -> ctx.incrementCounter(combinationId));
            }
        });
    }

    private List<MatchResult.MatchedRule> detectMatchesSoA(EvaluationContext ctx) {
        List<MatchResult.MatchedRule> matches = new ArrayList<>();
        for (int combinationId : ctx.touchedRules) {
            ctx.rulesEvaluated++;
            if (ctx.counters[combinationId] == model.getCombinationPredicateCount(combinationId)) {
                matches.add(new MatchResult.MatchedRule(
                        combinationId,
                        model.getCombinationRuleCode(combinationId),
                        model.getCombinationPriority(combinationId),
                        ""
                ));
            }
        }
        return matches;
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

    public EvaluatorMetrics getMetrics() { return metrics; }

    // (Context and Metrics inner classes remain the same)
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