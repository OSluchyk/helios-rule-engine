package os.toolset.ruleengine.core;

import it.unimi.dsi.fastutil.ints.*;
import org.roaringbitmap.IntIterator;
import org.roaringbitmap.RoaringBitmap;
import os.toolset.ruleengine.model.Event;
import os.toolset.ruleengine.model.MatchResult;
import os.toolset.ruleengine.model.Predicate;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Phase 4: High-performance rule evaluation engine using SoA memory layout
 * and weight-based predicate evaluation for optimal CPU cache efficiency.
 */
public class RuleEvaluator {
    private static final Logger logger = Logger.getLogger(RuleEvaluator.class.getName());

    private final EngineModel model;
    private final EvaluatorMetrics metrics;
    private final ThreadLocal<EvaluationContext> contextPool;

    public RuleEvaluator(EngineModel model) {
        this.model = Objects.requireNonNull(model);
        this.metrics = new EvaluatorMetrics();
        this.contextPool = ThreadLocal.withInitial(() -> new EvaluationContext(model.getNumRules()));
    }

    public MatchResult evaluate(Event event) {
        long startTime = System.nanoTime();
        EvaluationContext ctx = contextPool.get();
        ctx.reset();

        if (logger.isLoggable(Level.FINE)) {
            logger.fine("Evaluating event '" + event.getEventId() + "' with attributes: " + event.getFlattenedAttributes());
        }

        evaluatePredicatesWeighted(event, ctx);
        updateCounters(ctx);
        List<MatchResult.MatchedRule> allMatches = detectMatchesSoA(ctx);
        List<MatchResult.MatchedRule> selectedMatches = selectMatches(allMatches);

        long evaluationTime = System.nanoTime() - startTime;
        metrics.recordEvaluation(evaluationTime, ctx.predicatesEvaluated, ctx.rulesEvaluated);

        if (logger.isLoggable(Level.FINE)) {
            String matchedCodes = selectedMatches.stream().map(MatchResult.MatchedRule::ruleCode).collect(Collectors.joining(", "));
            logger.fine(String.format("Event '%s' evaluation complete in %.3f ms. Predicates evaluated: %d, Matches: [%s]",
                    event.getEventId(), evaluationTime / 1_000_000.0, ctx.predicatesEvaluated, matchedCodes));
        }

        return new MatchResult(
                event.getEventId(),
                selectedMatches,
                evaluationTime,
                ctx.predicatesEvaluated,
                ctx.rulesEvaluated
        );
    }

    /**
     * OPTIMIZED: Evaluate predicates by iterating through event attributes and looking up
     * the pre-sorted list of candidate predicates for that field.
     */
    private void evaluatePredicatesWeighted(Event event, EvaluationContext ctx) {
        Map<String, Object> flattenedEvent = event.getFlattenedAttributes();

        for (Map.Entry<String, Object> eventEntry : flattenedEvent.entrySet()) {
            List<Predicate> candidatePredicates = model.getFieldToPredicates().get(eventEntry.getKey());
            if (candidatePredicates != null) {
                // This list is already sorted by weight (cheapest first) by the compiler
                for (Predicate p : candidatePredicates) {
                    ctx.predicatesEvaluated++;
                    if (p.evaluate(eventEntry.getValue())) {
                        int predicateId = model.getPredicateId(p);
                        if (predicateId != -1) {
                            ctx.addTruePredicate(predicateId);
                        }
                    }
                }
            }
        }

        if (logger.isLoggable(Level.FINER)) {
            String truePredicateDetails = ctx.truePredicates.stream()
                    .map(model::getPredicate)
                    .map(Objects::toString)
                    .collect(Collectors.joining(", "));
            logger.finer("Event '" + event.getEventId() + "' matched " + ctx.truePredicates.size()
                    + " predicates: [" + truePredicateDetails + "]");
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

    /**
     * Detect matches using Structure of Arrays for optimal cache performance.
     */
    private List<MatchResult.MatchedRule> detectMatchesSoA(EvaluationContext ctx) {
        List<MatchResult.MatchedRule> matches = new ArrayList<>();
        int[] touchedArray = ctx.touchedRules.toIntArray();
        Arrays.sort(touchedArray); // Improve cache locality through sequential access

        for (int ruleId : touchedArray) {
            ctx.rulesEvaluated++;
            // Direct array access (cache-friendly)
            if (ctx.counters[ruleId] == model.getRulePredicateCount(ruleId)) {
                matches.add(new MatchResult.MatchedRule(
                        ruleId,
                        model.getRuleCode(ruleId),
                        model.getRulePriority(ruleId),
                        model.getRule(ruleId).getDescription() // Construct full Rule only when needed
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

