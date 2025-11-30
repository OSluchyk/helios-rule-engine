package com.helios.ruleengine.runtime.context;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.roaringbitmap.RoaringBitmap;

/**
 * Thread-local evaluation context with object pooling and matched rules
 * tracking.
 *
 * THREAD SAFETY: One instance per thread (ThreadLocal), no synchronization
 * needed.
 * MEMORY: Pooled and reused via reset(), zero allocations in steady state.
 *
 * PERFORMANCE OPTIMIZATIONS:
 * - Mutable matched rules list (avoid MatchedRule object creation during
 * evaluation)
 * - Pre-sized collections to minimize resizing
 * - Direct field access for hot paths (counters, touchedRules)
 * - Reusable RoaringBitmap buffer for intersection operations
 *
 * FIX: touchedRules is now IntSet for automatic deduplication
 * FIX: predicatesEvaluated public field now synchronized with internal counter
 */
public final class EvaluationContext {

    // Predicate evaluation results
    private final IntSet truePredicates;

    // Rule matching state
    // FIX: Changed from IntList to IntSet to automatically deduplicate
    private final IntSet touchedRules;
    public final int[] counters; // Public for hot-path access

    // Reusable bitmap buffer for intersection operations
    public final RoaringBitmap bitmapBuffer;

    // Matched rules tracking (mutable during evaluation)
    private final List<MutableMatchedRule> matchedRules;

    // Metrics
    private int predicatesEvaluatedCount;

    @Deprecated
    public EvaluationContext(int estimatedTouchedRules) {
        this(10000, estimatedTouchedRules);
    }

    public EvaluationContext(int numRules, int estimatedTouchedRules) {
        this.truePredicates = new IntOpenHashSet(256);
        this.touchedRules = new IntOpenHashSet(estimatedTouchedRules); // FIX: Now a Set
        this.counters = new int[numRules];
        this.bitmapBuffer = new RoaringBitmap();
        this.matchedRules = new ArrayList<>(32); // Pre-size for typical match count
        this.predicatesEvaluatedCount = 0;
    }

    /**
     * Reset context for reuse (object pooling).
     * Called at the start of each evaluation.
     *
     * FIX: Now also resets the public predicatesEvaluated field
     */
    public void reset() {
        truePredicates.clear();
        touchedRules.clear();
        Arrays.fill(counters, 0);
        bitmapBuffer.clear();
        matchedRules.clear();
        predicatesEvaluatedCount = 0;
        predicatesEvaluated = 0; // FIX: Synchronize public field
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
     *
     * FIX: Now automatically deduplicates using IntSet.
     */
    public void addTouchedRule(int ruleId) {
        touchedRules.add(ruleId);
    }

    /**
     * Get all rules that were touched.
     *
     * FIX: Returns IntSet instead of IntList for proper deduplication.
     */
    public IntSet getTouchedRules() {
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
     *
     * FIX: Now also increments the public predicatesEvaluated field
     */
    public void incrementPredicatesEvaluatedCount() {
        predicatesEvaluatedCount++;
        predicatesEvaluated++; // FIX: Synchronize public field
    }

    /**
     * Add to predicates evaluated count (for batch operations).
     *
     * FIX: Now also updates the public predicatesEvaluated field
     */
    public void addPredicatesEvaluated(int count) {
        predicatesEvaluatedCount += count;
        predicatesEvaluated += count; // FIX: Synchronize public field
    }

    /**
     * Get total predicates evaluated.
     *
     * COMPATIBILITY: This provides both field-style access (predicatesEvaluated)
     * and method-style access for the refactored code.
     */
    public int getPredicatesEvaluated() {
        return predicatesEvaluatedCount;
    }

    /**
     * Direct field access for performance-critical code.
     * Use this when avoiding method call overhead matters.
     *
     * FIX: This field is now properly synchronized with predicatesEvaluatedCount
     * in all increment and reset operations.
     */
    public int predicatesEvaluated = 0; // Now synchronized with internal counter

    /**
     * Add a matched rule to the results.
     * Uses mutable object to avoid allocation during hot evaluation path.
     */
    public void addMatchedRule(int ruleId, String ruleCode, int priority, String description) {
        matchedRules.add(new MutableMatchedRule(ruleId, ruleCode, priority, description));
    }

    /**
     * Get mutable matched rules list.
     * Used during evaluation to build results without allocation.
     */
    public List<MutableMatchedRule> getMutableMatchedRules() {
        return matchedRules;
    }

    /**
     * Mutable matched rule object - avoids allocation during evaluation.
     *
     * This is converted to immutable MatchResult.MatchedRule at the end of
     * evaluation.
     */
    public static final class MutableMatchedRule {
        private final int ruleId;
        private final String ruleCode;
        private final int priority;
        private final String description;

        public MutableMatchedRule(int ruleId, String ruleCode, int priority, String description) {
            this.ruleId = ruleId;
            this.ruleCode = ruleCode;
            this.priority = priority;
            this.description = description;
        }

        public int getRuleId() {
            return ruleId;
        }

        public String getRuleCode() {
            return ruleCode;
        }

        public int getPriority() {
            return priority;
        }

        public String getDescription() {
            return description;
        }
    }
}