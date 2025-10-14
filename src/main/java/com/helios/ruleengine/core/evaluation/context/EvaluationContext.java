package com.helios.ruleengine.core.evaluation.context;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;

import java.util.Arrays;

/**
 * Thread-local evaluation context with object pooling.
 *
 * THREAD SAFETY: One instance per thread (ThreadLocal), no synchronization needed.
 * MEMORY: Pooled and reused via reset(), zero allocations in steady state.
 *
 * @author Google L5 Engineering Standards
 */
public final class EvaluationContext {

    // Predicate evaluation results
    private final IntSet truePredicates;

    // Rule matching state
    private final IntList touchedRules;
    private final int[] counters;

    // Metrics
    private int predicatesEvaluatedCount;

    public EvaluationContext(int estimatedTouchedRules) {
        this.truePredicates = new IntOpenHashSet(256);
        this.touchedRules = new IntArrayList(estimatedTouchedRules);
        this.counters = new int[10000]; // Adjust based on max rules
        this.predicatesEvaluatedCount = 0;
    }

    /**
     * Reset context for reuse (object pooling).
     * Called at the start of each evaluation.
     */
    public void reset() {
        truePredicates.clear();
        touchedRules.clear();
        Arrays.fill(counters, 0);
        predicatesEvaluatedCount = 0;
    }

    /**
     * Record that a predicate evaluated to true.
     */
    public void addTruePredicate(int predicateId) {
        truePredicates.add(predicateId);
    }

    /**
     * Get all predicates that evaluated to true.
     */
    public IntSet getTruePredicates() {
        return truePredicates;
    }

    /**
     * Record that a rule was touched (had at least one predicate evaluated).
     */
    public void addTouchedRule(int ruleId) {
        if (!touchedRules.contains(ruleId)) {
            touchedRules.add(ruleId);
        }
    }

    /**
     * Get all rules that were touched.
     */
    public IntList getTouchedRules() {
        return touchedRules;
    }

    /**
     * Increment match counter for a rule.
     */
    public void incrementCounter(int ruleId) {
        counters[ruleId]++;
    }

    /**
     * Get match counter for a rule.
     */
    public int getCounter(int ruleId) {
        return counters[ruleId];
    }

    /**
     * Get all counters (for bulk processing).
     */
    public int[] getCounters() {
        return counters;
    }

    /**
     * Increment predicates evaluated count.
     */
    public void incrementPredicatesEvaluatedCount() {
        predicatesEvaluatedCount++;
    }

    /**
     * Add to predicates evaluated count (for batch operations).
     */
    public void addPredicatesEvaluated(int count) {
        predicatesEvaluatedCount += count;
    }

    /**
     * Get total predicates evaluated.
     */
    public int getPredicatesEvaluated() {
        return predicatesEvaluatedCount;
    }
}