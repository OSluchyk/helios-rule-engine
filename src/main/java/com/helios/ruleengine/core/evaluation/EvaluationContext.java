package com.helios.ruleengine.core.evaluation;

import it.unimi.dsi.fastutil.ints.*;
import com.helios.ruleengine.model.MatchResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Memory-optimized evaluation context using sparse data structures,
 * lazy allocation, and ACTUAL object pooling to minimize GC pressure.
 *
 * P0-2 FIX: Completely redesigned pooling to reuse mutable objects
 */
public class EvaluationContext {
    private final Int2IntOpenHashMap sparseCounters;
    private final IntArrayList truePredicates;
    private final IntOpenHashSet touchedRules;
    private final List<MutableMatchedRule> mutableMatchedRules;  // Changed from immutable
    private final Map<String, MatchResult.MatchedRule> workMap;
    private final int[] workBuffer;

    // --- FIXED OBJECT POOL for MatchedRule ---
    private final MatchedRulePool matchedRulePool;

    int predicatesEvaluated;
    int rulesEvaluated;

    public EvaluationContext(int estimatedTouchedRules) {
        this.sparseCounters = new Int2IntOpenHashMap(Math.min(estimatedTouchedRules, 1024));
        this.sparseCounters.defaultReturnValue(0);
        this.truePredicates = new IntArrayList(32);
        this.touchedRules = new IntOpenHashSet(estimatedTouchedRules);
        this.mutableMatchedRules = new ArrayList<>(16);
        this.workMap = new HashMap<>();
        this.workBuffer = new int[64];
        this.matchedRulePool = new MatchedRulePool(16);
    }

    /**
     * P0-2 FIX: Rent a MUTABLE MatchedRule from the pool
     */
    public MutableMatchedRule rentMatchedRule(int combinationId, String ruleCode, int priority, String description) {
        return matchedRulePool.acquire(combinationId, ruleCode, priority, description);
    }

    public void reset() {
        // Return all used rules to the pool BEFORE clearing the list
        matchedRulePool.releaseAll(mutableMatchedRules);

        sparseCounters.clear();
        truePredicates.clear();
        touchedRules.clear();
        mutableMatchedRules.clear();
        workMap.clear();
        predicatesEvaluated = 0;
        rulesEvaluated = 0;
    }

    /**
     * P0-2 FIX: Mutable MatchedRule that can be reused
     */
    public static class MutableMatchedRule {
        private int combinationId;
        private String ruleCode;
        private int priority;
        private String description;

        public MutableMatchedRule() {
            // Empty constructor for pooling
        }

        public void set(int combinationId, String ruleCode, int priority, String description) {
            this.combinationId = combinationId;
            this.ruleCode = ruleCode;
            this.priority = priority;
            this.description = description;
        }

        public int getCombinationId() {
            return combinationId;
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

        /**
         * Convert to immutable record for final result
         */
        public MatchResult.MatchedRule toImmutable() {
            return new MatchResult.MatchedRule(combinationId, ruleCode, priority, description);
        }

        @Override
        public String toString() {
            return String.format("MutableMatchedRule[id=%d, code=%s, priority=%d]",
                    combinationId, ruleCode, priority);
        }
    }

    /**
     * P0-2 FIX: Actual working object pool
     */
    private static class MatchedRulePool {
        private final ArrayList<MutableMatchedRule> pool;
        private final int maxSize;

        MatchedRulePool(int initialSize) {
            this.pool = new ArrayList<>(initialSize);
            this.maxSize = initialSize * 4; // Allow growth to 64

            // Pre-allocate initial pool
            for (int i = 0; i < initialSize; i++) {
                pool.add(new MutableMatchedRule());
            }
        }

        MutableMatchedRule acquire(int combinationId, String ruleCode, int priority, String description) {
            MutableMatchedRule rule;

            if (!pool.isEmpty()) {
                // FIXED: Actually reuse from pool
                rule = pool.remove(pool.size() - 1);
                rule.set(combinationId, ruleCode, priority, description);
            } else {
                // Pool exhausted, create new (will be added to pool later)
                rule = new MutableMatchedRule();
                rule.set(combinationId, ruleCode, priority, description);
            }

            return rule;
        }

        void releaseAll(List<MutableMatchedRule> rules) {
            // Return rules to pool if we haven't exceeded max size
            if (pool.size() + rules.size() <= maxSize) {
                pool.addAll(rules);
            } else {
                // Add what we can
                int available = maxSize - pool.size();
                if (available > 0) {
                    pool.addAll(rules.subList(0, available));
                }
                // Rest will be GC'd (acceptable for rare spikes)
            }
        }
    }

    // Accessors
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

    public Map<String, MatchResult.MatchedRule> getWorkMap() {
        return workMap;
    }

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

    /**
     * P0-2 FIX: Add mutable matched rule to working list
     */
    public void addMatchedRule(MutableMatchedRule rule) {
        mutableMatchedRules.add(rule);
    }

    /**
     * P0-2 FIX: Convert mutable rules to immutable for final result
     */
    public List<MatchResult.MatchedRule> getMatchedRules() {
        List<MatchResult.MatchedRule> immutableRules = new ArrayList<>(mutableMatchedRules.size());
        for (MutableMatchedRule mutable : mutableMatchedRules) {
            immutableRules.add(mutable.toImmutable());
        }
        return immutableRules;
    }

    /**
     * Get mutable matched rules for processing (avoids conversion until final result)
     */
    public List<MutableMatchedRule> getMutableMatchedRules() {
        return mutableMatchedRules;
    }
}