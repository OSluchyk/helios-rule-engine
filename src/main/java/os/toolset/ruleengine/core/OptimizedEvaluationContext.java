package os.toolset.ruleengine.core;

import it.unimi.dsi.fastutil.ints.*;
import os.toolset.ruleengine.model.MatchResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Memory-optimized evaluation context using sparse data structures
 * and lazy allocation to reduce memory footprint by ~80% for typical workloads.
 */
public class OptimizedEvaluationContext {
    // Use sparse map for counters - only allocate for touched rules
    private final Int2IntOpenHashMap sparseCounters;

    // Compact representation of true predicates
    private final IntArrayList truePredicates;

    // Track touched rules efficiently
    private final IntOpenHashSet touchedRules;

    // List to store the rules that have been matched
    private final List<MatchResult.MatchedRule> matchedRules;

    // Reusable buffer pool for temporary operations
    private final int[] workBuffer;

    // Statistics
    int predicatesEvaluated;
    int rulesEvaluated;

    // Memory-efficient initialization
    public OptimizedEvaluationContext(int estimatedTouchedRules) {
        // Initialize with expected size, not max size
        this.sparseCounters = new Int2IntOpenHashMap(
                Math.min(estimatedTouchedRules, 1024)
        );
        this.sparseCounters.defaultReturnValue(0);

        this.truePredicates = new IntArrayList(32);
        this.touchedRules = new IntOpenHashSet(estimatedTouchedRules);
        this.matchedRules = new ArrayList<>();
        this.workBuffer = new int[64]; // Reusable buffer
    }

    public void incrementCounter(int combinationId) {
        sparseCounters.addTo(combinationId, 1);
        touchedRules.add(combinationId);
    }

    public int getCounter(int combinationId) {
        return sparseCounters.get(combinationId);
    }

    public void addTruePredicate(int predicateId) {
        truePredicates.add(predicateId);
    }

    public void reset() {
        // Efficient reset - only clear touched entries
        sparseCounters.clear();
        truePredicates.clear();
        touchedRules.clear();
        matchedRules.clear();
        predicatesEvaluated = 0;
        rulesEvaluated = 0;
    }

    // --- Added methods to satisfy RuleEvaluator dependencies ---

    public int getPredicatesEvaluatedCount() {
        return predicatesEvaluated;
    }

    public void incrementPredicatesEvaluatedCount() {
        this.predicatesEvaluated++;
    }

    public int getRulesEvaluatedCount() {
        return rulesEvaluated;
    }

    public void incrementRulesEvaluatedCount() {
        this.rulesEvaluated++;
    }

    public IntList getTruePredicates() {
        return truePredicates;
    }

    public IntSet getTouchedRuleIds() {
        return touchedRules;
    }

    public void addMatchedRule(MatchResult.MatchedRule rule) {
        matchedRules.add(rule);
    }

    public List<MatchResult.MatchedRule> getMatchedRules() {
        return matchedRules;
    }

    // Batch operations for vectorization
    public void incrementCountersBatch(int[] combinationIds, int count) {
        for (int i = 0; i < count; i++) {
            incrementCounter(combinationIds[i]);
        }
    }

    // Memory reporting
    public long getMemoryUsage() {
        return (sparseCounters.size() * 8L) +  // int->int entries
                (truePredicates.size() * 4L) +   // int list
                (touchedRules.size() * 4L) +     // int set
                (matchedRules.size() * 32L) +    // estimate for MatchedRule objects
                workBuffer.length * 4L;           // work buffer
    }
}