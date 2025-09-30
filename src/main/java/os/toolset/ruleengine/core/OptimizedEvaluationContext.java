package os.toolset.ruleengine.core;

import it.unimi.dsi.fastutil.ints.*;
import os.toolset.ruleengine.model.MatchResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Memory-optimized evaluation context using sparse data structures,
 * lazy allocation, and object pooling to minimize GC pressure.
 */
public class OptimizedEvaluationContext {
    private final Int2IntOpenHashMap sparseCounters;
    private final IntArrayList truePredicates;
    private final IntOpenHashSet touchedRules;
    private final List<MatchResult.MatchedRule> matchedRules;
    private final Map<String, MatchResult.MatchedRule> workMap;
    private final int[] workBuffer;

    // --- OBJECT POOL for MatchedRule ---
    private final MatchedRulePool matchedRulePool;

    int predicatesEvaluated;
    int rulesEvaluated;

    public OptimizedEvaluationContext(int estimatedTouchedRules) {
        this.sparseCounters = new Int2IntOpenHashMap(Math.min(estimatedTouchedRules, 1024));
        this.sparseCounters.defaultReturnValue(0);
        this.truePredicates = new IntArrayList(32);
        this.touchedRules = new IntOpenHashSet(estimatedTouchedRules);
        this.matchedRules = new ArrayList<>(16);
        this.workMap = new HashMap<>();
        this.workBuffer = new int[64];
        this.matchedRulePool = new MatchedRulePool(16); // Initialize pool
    }

    // Rent a MatchedRule from the pool instead of creating a new one
    public MatchResult.MatchedRule rentMatchedRule(int combinationId, String ruleCode, int priority, String description) {
        return matchedRulePool.acquire(combinationId, ruleCode, priority, description);
    }

    public void reset() {
        // Return all used rules to the pool
        matchedRulePool.releaseAll(matchedRules);

        sparseCounters.clear();
        truePredicates.clear();
        touchedRules.clear();
        matchedRules.clear();
        workMap.clear();
        predicatesEvaluated = 0;
        rulesEvaluated = 0;
    }

    // Inner class for the MatchedRule object pool
    private static class MatchedRulePool {
        private final ArrayList<MatchResult.MatchedRule> pool;
        private final int maxSize;

        MatchedRulePool(int initialSize) {
            this.pool = new ArrayList<>(initialSize);
            this.maxSize = initialSize * 2; // Prevent unbounded growth
            for (int i = 0; i < initialSize; i++) {
                pool.add(new MatchResult.MatchedRule(0, "", 0, ""));
            }
        }

        MatchResult.MatchedRule acquire(int combinationId, String ruleCode, int priority, String description) {
            if (!pool.isEmpty()) {
                MatchResult.MatchedRule rule = pool.remove(pool.size() - 1);
                // Re-initialize the object with new data
                return new MatchResult.MatchedRule(combinationId, ruleCode, priority, description);
            }
            // Pool is empty, create a new object
            return new MatchResult.MatchedRule(combinationId, ruleCode, priority, description);
        }

        void releaseAll(List<MatchResult.MatchedRule> rules) {
            if (pool.size() < maxSize) {
                pool.addAll(rules);
            }
        }
    }

    // No changes to other methods...
    public void incrementCounter(int combinationId) { sparseCounters.addTo(combinationId, 1); touchedRules.add(combinationId); }
    public int getCounter(int combinationId) { return sparseCounters.get(combinationId); }
    public void addTruePredicate(int predicateId) { truePredicates.add(predicateId); }
    public Map<String, MatchResult.MatchedRule> getWorkMap() { return workMap; }
    public int getPredicatesEvaluatedCount() { return predicatesEvaluated; }
    public void incrementPredicatesEvaluatedCount() { this.predicatesEvaluated++; }
    public int getRulesEvaluatedCount() { return rulesEvaluated; }
    public void incrementRulesEvaluatedCount() { this.rulesEvaluated++; }
    public IntList getTruePredicates() { return truePredicates; }
    public IntSet getTouchedRuleIds() { return touchedRules; }
    public void addMatchedRule(MatchResult.MatchedRule rule) { matchedRules.add(rule); }
    public List<MatchResult.MatchedRule> getMatchedRules() { return matchedRules; }
}