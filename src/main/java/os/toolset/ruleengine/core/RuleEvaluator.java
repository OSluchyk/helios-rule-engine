package os.toolset.ruleengine.core;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import org.roaringbitmap.IntIterator;
import org.roaringbitmap.RoaringBitmap;
import os.toolset.ruleengine.model.Event;
import os.toolset.ruleengine.model.MatchResult;
import os.toolset.ruleengine.model.Predicate;
import os.toolset.ruleengine.model.Rule;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * High-performance rule evaluation engine using high-performance collections.
 */
public class RuleEvaluator {
    private static final Logger logger = Logger.getLogger(RuleEvaluator.class.getName());

    private final EngineModel model;
    private final EvaluatorMetrics metrics;
    private final ThreadLocal<EvaluationContext> contextPool;

    public RuleEvaluator(EngineModel model) {
        this.model = Objects.requireNonNull(model);
        this.metrics = new EvaluatorMetrics();
        this.contextPool = ThreadLocal.withInitial(() -> new EvaluationContext(model.getRuleStore().length));
    }

    public MatchResult evaluate(Event event) {
        long startTime = System.nanoTime();
        EvaluationContext ctx = contextPool.get();
        ctx.reset();

        evaluatePredicates(event, ctx);
        updateCounters(ctx);
        List<MatchResult.MatchedRule> matches = detectMatches(ctx);

        long evaluationTime = System.nanoTime() - startTime;
        metrics.recordEvaluation(evaluationTime, ctx.predicatesEvaluated, ctx.rulesEvaluated);

        return new MatchResult(
                event.getEventId(),
                matches,
                evaluationTime,
                ctx.predicatesEvaluated,
                ctx.rulesEvaluated
        );
    }

    private void evaluatePredicates(Event event, EvaluationContext ctx) {
        Map<String, Object> flattenedEvent = event.getFlattenedAttributes();
        for (Map.Entry<String, Object> eventEntry : flattenedEvent.entrySet()) {
            Predicate p = new Predicate(eventEntry.getKey(), eventEntry.getValue());
            int predicateId = model.getPredicateId(p);
            if (predicateId != -1) { // fastutil default 'not found' value
                ctx.addTruePredicate(predicateId);
            }
            ctx.predicatesEvaluated++;
        }
    }

    private void updateCounters(EvaluationContext ctx) {
        // fastutil's forEach is efficient for primitive iteration
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

    private List<MatchResult.MatchedRule> detectMatches(EvaluationContext ctx) {
        List<MatchResult.MatchedRule> matches = new ArrayList<>();
        // Iterate efficiently over the primitive set of touched rules
        for (int ruleId : ctx.touchedRules) {
            Rule rule = model.getRule(ruleId);
            if (rule != null && ctx.counters[ruleId] == rule.getPredicateCount()) {
                matches.add(new MatchResult.MatchedRule(
                        rule.getId(),
                        rule.getRuleCode(),
                        rule.getPriority(),
                        rule.getDescription()
                ));
            }
            ctx.rulesEvaluated++;
        }
        // Sorting is required to meet the functional need for prioritized results.
        // For future optimization, if this becomes a bottleneck with many matches,
        // a bounded priority queue could be a more efficient alternative.
        matches.sort(Comparator.comparingInt(MatchResult.MatchedRule::priority).reversed());
        return matches;
    }

    public EvaluatorMetrics getMetrics() {
        return metrics;
    }

    /**
     * Thread-local evaluation context using high-performance primitive collections.
     */
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

