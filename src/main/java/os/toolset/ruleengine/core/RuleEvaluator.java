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
 * Phase 5: Rule evaluation engine adapted for Dictionary Encoding.
 */
public class RuleEvaluator {
    private static final Logger logger = Logger.getLogger(RuleEvaluator.class.getName());

    private final EngineModel model;
    private final EvaluatorMetrics metrics;
    private final ThreadLocal<EvaluationContext> contextPool;
    private final BaseConditionCache cache;
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
    }

    private static BaseConditionCache createDefaultCache() {
        return new InMemoryBaseConditionCache.Builder()
                .maxSize(10_000)
                .defaultTtl(5, TimeUnit.MINUTES)
                .build();
    }

    public MatchResult evaluate(Event event) {
        long startTime = System.nanoTime();
        EvaluationContext ctx = contextPool.get();
        ctx.reset();

        evaluatePredicates(event, ctx);
        updateCounters(ctx);
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
    }

    private void evaluatePredicates(Event event, EvaluationContext ctx) {
        Int2ObjectMap<Object> encodedEvent = event.getEncodedAttributes(model.getFieldDictionary(), model.getValueDictionary());

        for (Int2ObjectMap.Entry<Object> entry : encodedEvent.int2ObjectEntrySet()) {
            int fieldId = entry.getIntKey();
            Object eventValue = entry.getValue();

            List<Predicate> candidatePredicates = model.getFieldToPredicates().get(fieldId);
            if (candidatePredicates != null) {
                for (Predicate p : candidatePredicates) {
                    ctx.predicatesEvaluated++;
                    if (p.evaluate(eventValue)) {
                        int predicateId = model.getPredicateId(p);
                        if (predicateId != -1) {
                            ctx.addTruePredicate(predicateId);
                        }
                    }
                }
            }
        }
    }

    private void updateCounters(EvaluationContext ctx) {
        ctx.truePredicates.forEach((int predicateId) -> {
            RoaringBitmap affectedRules = model.getInvertedIndex().get(predicateId);
            if (affectedRules != null) {
                IntIterator it = affectedRules.getIntIterator();
                while (it.hasNext()) {
                    ctx.incrementCounter(it.next());
                }
            }
        });
    }

    private List<MatchResult.MatchedRule> detectMatchesSoA(EvaluationContext ctx) {
        List<MatchResult.MatchedRule> matches = new ArrayList<>();
        int[] touchedArray = ctx.touchedRules.toIntArray();
        Arrays.sort(touchedArray);

        for (int ruleId : touchedArray) {
            ctx.rulesEvaluated++;
            if (ctx.counters[ruleId] == model.getRulePredicateCount(ruleId)) {
                matches.add(new MatchResult.MatchedRule(
                        ruleId,
                        model.getRuleCode(ruleId),
                        model.getRulePriority(ruleId),
                        model.getRule(ruleId).getDescription()
                ));
            }
        }
        return matches;
    }

    private List<MatchResult.MatchedRule> selectMatches(List<MatchResult.MatchedRule> allMatches) {
        if (allMatches.size() <= 1) {
            return allMatches;
        }
        Map<String, MatchResult.MatchedRule> maxPriorityMatches = new HashMap<>();
        for (MatchResult.MatchedRule match : allMatches) {
            maxPriorityMatches.merge(match.ruleCode(), match,
                    (existing, newMatch) -> newMatch.priority() > existing.priority() ? newMatch : existing);
        }
        List<MatchResult.MatchedRule> selected = new ArrayList<>(maxPriorityMatches.values());
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

        EvaluationContext(int maxRules) {
            this.counters = new int[maxRules];
            this.truePredicates = new IntArrayList();
            this.touchedRules = new IntOpenHashSet();
        }

        void reset() {
            for (int ruleId : touchedRules) {
                counters[ruleId] = 0;
            }
            truePredicates.clear();
            touchedRules.clear();
            predicatesEvaluated = 0;
            rulesEvaluated = 0;
        }

        void addTruePredicate(int predicateId) {
            truePredicates.add(predicateId);
        }

        void incrementCounter(int ruleId) {
            counters[ruleId]++;
            touchedRules.add(ruleId);
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