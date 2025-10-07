package com.helios.ruleengine.core;

import it.unimi.dsi.fastutil.ints.*;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.roaringbitmap.RoaringBitmap;
import com.helios.ruleengine.core.bitmap.AdaptiveBitmapManager;
import com.helios.ruleengine.model.Predicate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class EngineModel {

    private final Dictionary fieldDictionary;
    private final Dictionary valueDictionary;
    private final Object2IntMap<Predicate> predicateRegistry;
    private final Int2ObjectMap<Predicate> predicateLookup;
    private final Int2ObjectMap<List<Predicate>> fieldToPredicates;
    private final Int2ObjectMap<IntList> combinationIdToPredicateIds;
    private final Int2ObjectMap<RoaringBitmap> invertedIndex;
    private final int numCombinations;
    private final int[] predicateCounts;
    private final IntList[] combinationToPredicateIds;
    private final Object2ObjectMap<String, IntList> logicalRuleToCombinationIds;
    private final Int2ObjectMap<String> combinationIdToLogicalRuleCode;
    private final Int2IntMap combinationIdToPriority;
    private final EngineStats stats;
    private final List<Predicate> sortedPredicates;
    private final Int2FloatMap fieldMinWeights;

    private EngineModel(Builder builder) {
        this.fieldDictionary = builder.fieldDictionary;
        this.valueDictionary = builder.valueDictionary;
        this.predicateRegistry = builder.predicateRegistry;
        this.predicateLookup = builder.predicateLookup;
        this.fieldToPredicates = builder.fieldToPredicates;
        this.combinationIdToPredicateIds = builder.idToCombinationMap;
        this.invertedIndex = builder.invertedIndex;
        this.numCombinations = builder.getUniqueCombinationCount();
        this.predicateCounts = builder.predicateCounts;
        this.combinationToPredicateIds = builder.combinationToPredicateIds;
        this.logicalRuleToCombinationIds = builder.logicalRuleToCombinationIds;
        this.combinationIdToLogicalRuleCode = builder.combinationIdToLogicalRuleCode;
        this.combinationIdToPriority = builder.combinationIdToPriority;
        this.stats = builder.stats;
        this.sortedPredicates = builder.sortedPredicates;
        this.fieldMinWeights = builder.fieldMinWeights;
    }

    public int getNumRules() { return numCombinations; }
    public Dictionary getFieldDictionary() { return fieldDictionary; }
    public Dictionary getValueDictionary() { return valueDictionary; }
    public Object2IntMap<Predicate> getPredicateRegistry() { return predicateRegistry; }
    public Int2ObjectMap<RoaringBitmap> getInvertedIndex() { return invertedIndex; }
    public Int2ObjectMap<List<Predicate>> getFieldToPredicates() { return fieldToPredicates; }
    public Predicate getPredicate(int id) { return predicateLookup.get(id); }
    public int getPredicateId(Predicate p) { return predicateRegistry.getInt(p); }
    public EngineStats getStats() { return stats; }
    public int getCombinationPredicateCount(int combinationId) { return predicateCounts[combinationId]; }
    public String getCombinationRuleCode(int combinationId) { return combinationIdToLogicalRuleCode.get(combinationId); }
    public int getCombinationPriority(int combinationId) { return combinationIdToPriority.get(combinationId); }
    public IntList getCombinationPredicateIds(int combinationId) { return combinationToPredicateIds[combinationId]; }
    public List<Predicate> getSortedPredicates() { return sortedPredicates; }
    public float getFieldMinWeight(int fieldId) { return fieldMinWeights.get(fieldId); }


    public static class Builder {
        Dictionary fieldDictionary;
        Dictionary valueDictionary;
        final Object2IntMap<Predicate> predicateRegistry = new Object2IntOpenHashMap<>();
        final Int2ObjectMap<Predicate> predicateLookup = new Int2ObjectOpenHashMap<>();
        final Int2ObjectMap<List<Predicate>> fieldToPredicates = new Int2ObjectOpenHashMap<>();
        final Int2ObjectMap<RoaringBitmap> invertedIndex = new Int2ObjectOpenHashMap<>();
        EngineStats stats;
        List<Predicate> sortedPredicates;
        final Int2FloatMap fieldMinWeights = new Int2FloatOpenHashMap();

        private final Object2IntMap<IntList> combinationToIdMap = new Object2IntOpenHashMap<>();
        final Int2ObjectMap<IntList> idToCombinationMap = new Int2ObjectOpenHashMap<>();
        private int totalExpandedCombinations = 0;

        final Object2ObjectMap<String, IntList> logicalRuleToCombinationIds = new Object2ObjectOpenHashMap<>();
        final Int2ObjectMap<String> combinationIdToLogicalRuleCode = new Int2ObjectOpenHashMap<>();
        final Int2IntMap combinationIdToPriority = new Int2IntOpenHashMap();
        private final AdaptiveBitmapManager adaptiveBitmapManager = new AdaptiveBitmapManager();

        int[] predicateCounts;
        IntList[] combinationToPredicateIds;

        public Builder() {
            combinationToIdMap.defaultReturnValue(-1);
        }

        public int registerPredicate(Predicate predicate) {
            // **FIXED**: Explicitly type the lambda parameter to resolve ambiguity.
            return predicateRegistry.computeIfAbsent(predicate, (Predicate p) -> {
                int id = predicateRegistry.size();
                predicateLookup.put(id, p);
                fieldToPredicates.computeIfAbsent(p.fieldId(), k -> new ArrayList<>()).add(p);
                return id;
            });
        }

        public int registerCombination(IntList predicateIds) {
            totalExpandedCombinations++;
            int combinationId = combinationToIdMap.getInt(predicateIds);
            if (combinationId == -1) {
                combinationId = combinationToIdMap.size();
                combinationToIdMap.put(predicateIds, combinationId);
                idToCombinationMap.put(combinationId, predicateIds);
                for (int predId : predicateIds) {
                    invertedIndex.computeIfAbsent(predId, k -> adaptiveBitmapManager.getOptimalBitmap(totalExpandedCombinations)).add(combinationId);
                }
            }
            return combinationId;
        }

        public void addLogicalRuleMapping(String ruleCode, int priority, String description, int combinationId) {
            logicalRuleToCombinationIds.computeIfAbsent(ruleCode, k -> new IntArrayList()).add(combinationId);
            combinationIdToLogicalRuleCode.put(combinationId, ruleCode);
            combinationIdToPriority.merge(combinationId, priority, Math::max);
        }

        public int getPredicateCount() { return predicateRegistry.size(); }
        public int getUniqueCombinationCount() { return combinationToIdMap.size(); }
        public int getTotalExpandedCombinations() { return totalExpandedCombinations; }

        public Builder withStats(EngineStats stats) { this.stats = stats; return this; }
        public Builder withFieldDictionary(Dictionary d) { this.fieldDictionary = d; return this; }
        public Builder withValueDictionary(Dictionary d) { this.valueDictionary = d; return this; }

        private void finalizeOptimizedStructures() {
            int numUniqueCombinations = getUniqueCombinationCount();

            predicateCounts = new int[numUniqueCombinations];
            combinationToPredicateIds = new IntList[numUniqueCombinations];

            for (int i = 0; i < numUniqueCombinations; i++) {
                IntList pIds = idToCombinationMap.get(i);
                predicateCounts[i] = pIds.size();
                combinationToPredicateIds[i] = pIds;
            }

            // Phase 4: Sort all registered predicates by weight (selectivity)
            sortedPredicates = new ArrayList<>(predicateLookup.values());
            sortedPredicates.sort(Comparator.comparing(Predicate::weight));

            // Pre-compute the minimum weight for each field
            fieldMinWeights.defaultReturnValue(Float.MAX_VALUE);
            for (Predicate p : sortedPredicates) {
                if (!fieldMinWeights.containsKey(p.fieldId())) {
                    fieldMinWeights.put(p.fieldId(), p.weight());
                }
            }
        }

        public EngineModel build() {
            predicateRegistry.defaultReturnValue(-1);
            finalizeOptimizedStructures();
            return new EngineModel(this);
        }
    }

    public record EngineStats(
            int totalRules,
            int totalPredicates,
            long compilationTimeNanos,
            Map<String, Object> metadata
    ) {}
}

