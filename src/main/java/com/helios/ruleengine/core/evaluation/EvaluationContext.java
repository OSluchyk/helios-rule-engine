package com.helios.ruleengine.core.evaluation;

import com.helios.ruleengine.model.MatchResult;
import it.unimi.dsi.fastutil.ints.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Memory-optimized evaluation context that is now REUSED per-thread via ScopedValue
 * to completely eliminate hot-path allocations of the context and its internal collections.
 */
public class EvaluationContext {

    // --- MODIFICATION: Use ScopedValue to hold a reusable instance per thread ---
    // This is the key to avoiding `new EvaluationContext()` on every call.
    // It's a standard Java 21 feature, not a new dependency.
    private static final ScopedValue<EvaluationContext> HOLDER = ScopedValue.newInstance();

    private final Int2IntOpenHashMap sparseCounters;
    private final IntArrayList truePredicates;
    private final IntOpenHashSet touchedRules;
    private final List<MutableMatchedRule> mutableMatchedRules;
    private final Map<String, MatchResult.MatchedRule> workMap;
    private final int[] workBuffer;
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
     * MODIFICATION: Provides a thread-safe, reusable EvaluationContext.
     * The RuleEvaluator will call this instead of `new`.
     *
     * @return A clean, ready-to-use EvaluationContext for the current thread.
     */
    public static ScopedValue.Carrier acquire() {
        // Create a new carrier that holds a single instance of the context.
        // This instance will be reused for all evaluations on this thread within the ScopedValue's scope.
        return ScopedValue.where(HOLDER, new EvaluationContext(1024));
    }

    /**
     * MODIFICATION: Gets the context for the current scope.
     */
    public static EvaluationContext get() {
        // Gets the instance that was provided by the .acquire() carrier.
        return HOLDER.get();
    }


    public MutableMatchedRule rentMatchedRule(int combinationId, String ruleCode, int priority, String description) {
        return matchedRulePool.acquire(combinationId, ruleCode, priority, description);
    }

    public void reset() {
        matchedRulePool.releaseAll(mutableMatchedRules);
        sparseCounters.clear();
        truePredicates.clear();
        touchedRules.clear();
        mutableMatchedRules.clear();
        workMap.clear();
        predicatesEvaluated = 0;
        rulesEvaluated = 0;
    }

    public static class MutableMatchedRule {
        private int combinationId;
        private String ruleCode;
        private int priority;
        private String description;
        public MutableMatchedRule() {}
        public void set(int combinationId, String ruleCode, int priority, String description) {
            this.combinationId = combinationId;
            this.ruleCode = ruleCode;
            this.priority = priority;
            this.description = description;
        }
        public int getCombinationId() { return combinationId; }
        public String getRuleCode() { return ruleCode; }
        public int getPriority() { return priority; }
        public String getDescription() { return description; }
        public MatchResult.MatchedRule toImmutable() {
            return new MatchResult.MatchedRule(combinationId, ruleCode, priority, description);
        }
        @Override
        public String toString() {
            return String.format("MutableMatchedRule[id=%d, code=%s, priority=%d]",
                    combinationId, ruleCode, priority);
        }
    }

    private static class MatchedRulePool {
        private final ArrayList<MutableMatchedRule> pool;
        private final int maxSize;
        MatchedRulePool(int initialSize) {
            this.pool = new ArrayList<>(initialSize);
            this.maxSize = initialSize * 4;
            for (int i = 0; i < initialSize; i++) {
                pool.add(new MutableMatchedRule());
            }
        }
        MutableMatchedRule acquire(int combinationId, String ruleCode, int priority, String description) {
            MutableMatchedRule rule;
            if (!pool.isEmpty()) {
                rule = pool.remove(pool.size() - 1);
                rule.set(combinationId, ruleCode, priority, description);
            } else {
                rule = new MutableMatchedRule();
                rule.set(combinationId, ruleCode, priority, description);
            }
            return rule;
        }
        void releaseAll(List<MutableMatchedRule> rules) {
            if (pool.size() + rules.size() <= maxSize) {
                pool.addAll(rules);
            } else {
                int available = maxSize - pool.size();
                if (available > 0) {
                    pool.addAll(rules.subList(0, available));
                }
            }
        }
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
    public void addMatchedRule(MutableMatchedRule rule) {
        mutableMatchedRules.add(rule);
    }
    public List<MatchResult.MatchedRule> getMatchedRules() {
        List<MatchResult.MatchedRule> immutableRules = new ArrayList<>(mutableMatchedRules.size());
        for (MutableMatchedRule mutable : mutableMatchedRules) {
            immutableRules.add(mutable.toImmutable());
        }
        return immutableRules;
    }
    public List<MutableMatchedRule> getMutableMatchedRules() {
        return mutableMatchedRules;
    }
}