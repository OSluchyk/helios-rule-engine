package com.helios.ruleengine.core.model;

import com.helios.ruleengine.model.Predicate;
import com.helios.ruleengine.model.RuleDefinition;
import it.unimi.dsi.fastutil.ints.*;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.roaringbitmap.RoaringBitmap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * P4-A FIX: The core data model for the rule engine, now optimized with a
 * Structure of Arrays (SoA) layout. This version has been corrected to resolve
 * all compilation errors and ensure full compatibility with the existing codebase.
 */
public final class EngineModel {

    // --- Core Data Structures (Common) ---
    private final Dictionary fieldDictionary;
    private final Dictionary valueDictionary;
    private final Predicate[] uniquePredicates;
    private final Int2ObjectMap<RoaringBitmap> invertedIndex;
    private final EngineStats stats;
    private final List<Predicate> sortedPredicates;
    private final Int2FloatMap fieldMinWeights;
    private final SelectionStrategy selectionStrategy;
    private final RuleDefinition[] ruleDefinitions;
    private final Int2IntMap familyPriorities;
    private final Int2ObjectMap<List<Predicate>> fieldToPredicates;
    private final Object2IntMap<Predicate> predicateRegistry;


    // --- SoA Layout for Hot Data ---
    private final int[] predicateCounts;
    private final int[] priorities;
    private final String[] ruleCodes;
    private final IntList[] combinationToPredicateIds;

    private EngineModel(Builder builder) {
        this.fieldDictionary = builder.fieldDictionary;
        this.valueDictionary = builder.valueDictionary;
        this.uniquePredicates = builder.uniquePredicates;
        this.invertedIndex = builder.invertedIndex;
        this.stats = builder.stats;
        this.sortedPredicates = builder.sortedPredicates;
        this.fieldMinWeights = builder.fieldMinWeights;
        this.selectionStrategy = builder.selectionStrategy;
        this.ruleDefinitions = builder.ruleDefinitions;
        this.familyPriorities = builder.familyPriorities;
        this.fieldToPredicates = builder.fieldToPredicates;
        this.predicateRegistry = builder.predicateRegistry;

        this.predicateCounts = builder.predicateCounts;
        this.priorities = builder.priorities;
        this.ruleCodes = builder.ruleCodes;
        this.combinationToPredicateIds = builder.combinationToPredicateIds;
    }

    // --- Public Accessors (Preserved for Compatibility) ---

    public int getNumRules() { return predicateCounts.length; }
    public Dictionary getFieldDictionary() { return fieldDictionary; }
    public Dictionary getValueDictionary() { return valueDictionary; }
    public Predicate[] getUniquePredicates() { return uniquePredicates; }
    public Int2ObjectMap<RoaringBitmap> getInvertedIndex() { return invertedIndex; }
    public EngineStats getStats() { return stats; }
    public List<Predicate> getSortedPredicates() { return sortedPredicates; }
    public float getFieldMinWeight(int fieldId) { return fieldMinWeights.get(fieldId); }
    public SelectionStrategy getSelectionStrategy() { return selectionStrategy; }
    public RuleDefinition[] getRuleDefinitions() { return ruleDefinitions; }
    public Int2IntMap getFamilyPriorities() { return familyPriorities; }
    public Predicate getPredicate(int id) { return (id >= 0 && id < uniquePredicates.length) ? uniquePredicates[id] : null; }
    public int getPredicateId(Predicate p) { return predicateRegistry.getInt(p); }
    public Int2ObjectMap<List<Predicate>> getFieldToPredicates() { return fieldToPredicates; }


    // --- SoA Data Accessors ---

    public int[] getPredicateCounts() { return predicateCounts; }
    public int[] getPriorities() { return priorities; }
    public IntList getCombinationPredicateIds(int combinationId) { return combinationToPredicateIds[combinationId]; }
    public String getCombinationRuleCode(int combinationId) { return ruleCodes[combinationId]; }
    public int getCombinationPriority(int combinationId) { return priorities[combinationId]; }
    public int getCombinationPredicateCount(int combinationId) { return predicateCounts[combinationId]; }


    public static class Builder {
        Dictionary fieldDictionary;
        Dictionary valueDictionary;
        Predicate[] uniquePredicates;
        final Int2ObjectMap<RoaringBitmap> invertedIndex = new Int2ObjectOpenHashMap<>();
        EngineStats stats;
        List<Predicate> sortedPredicates;
        final Int2FloatMap fieldMinWeights = new Int2FloatOpenHashMap();
        SelectionStrategy selectionStrategy = SelectionStrategy.ALL_MATCHES;
        RuleDefinition[] ruleDefinitions;
        Int2IntMap familyPriorities;
        final Int2ObjectMap<List<Predicate>> fieldToPredicates = new Int2ObjectOpenHashMap<>();
        final Object2IntMap<Predicate> predicateRegistry = new Object2IntOpenHashMap<>();


        private final Object2IntMap<IntList> combinationToIdMap = new Object2IntOpenHashMap<>();
        private final Int2ObjectMap<IntList> idToCombinationMap = new Int2ObjectOpenHashMap<>();
        private int totalExpandedCombinations = 0;

        int[] predicateCounts;
        int[] priorities;
        String[] ruleCodes;
        IntList[] combinationToPredicateIds;

        public Builder() {
            combinationToIdMap.defaultReturnValue(-1);
        }

        public int registerPredicate(Predicate predicate) {
            // Explicitly type the lambda parameter to resolve ambiguity.
            return predicateRegistry.computeIfAbsent(predicate, (Predicate p) -> {
                int id = predicateRegistry.size();
                fieldToPredicates.computeIfAbsent(p.fieldId(), k -> new ArrayList<>()).add(p);
                return id;
            });
        }

        public int registerCombination(IntList predicateIds) {
            totalExpandedCombinations++;
            int combinationId = combinationToIdMap.getInt(predicateIds);
            if (combinationId == -1) {
                combinationId = combinationToIdMap.size();
                combinationToIdMap.put(new IntArrayList(predicateIds), combinationId);
                idToCombinationMap.put(combinationId, new IntArrayList(predicateIds));
            }
            return combinationId;
        }

        public void addLogicalRuleMapping(String ruleCode, Integer priority, String description, int combinationId) {
            if (ruleDefinitions != null && combinationId < ruleDefinitions.length) {
                if (ruleDefinitions[combinationId] == null) {
                    // CORRECTED: Call the RuleDefinition constructor with the right arguments.
                    ruleDefinitions[combinationId] = new RuleDefinition(ruleCode, new ArrayList<>(), priority, description, true);
                }
            }
        }

        public void buildInvertedIndex() {
            for(Object2IntMap.Entry<IntList> entry : combinationToIdMap.object2IntEntrySet()){
                IntList predicateIds = entry.getKey();
                int combinationId = entry.getIntValue();
                for (int predId : predicateIds) {
                    invertedIndex.computeIfAbsent(predId, k -> new RoaringBitmap()).add(combinationId);
                }
            }
        }

        public int getUniqueCombinationCount() { return combinationToIdMap.size(); }
        public int getTotalExpandedCombinations() { return totalExpandedCombinations; }
        public int getPredicateCount() { return predicateRegistry.size(); }

        public Builder withStats(EngineStats stats) { this.stats = stats; return this; }
        public Builder withFieldDictionary(Dictionary d) { this.fieldDictionary = d; return this; }
        public Builder withValueDictionary(Dictionary d) { this.valueDictionary = d; return this; }
        public Builder withUniquePredicates(Predicate[] p) { this.uniquePredicates = p; return this; }
        public Builder withSelectionStrategy(SelectionStrategy s) { this.selectionStrategy = s; return this; }
        public Builder withRuleDefinitions(RuleDefinition[] defs) { this.ruleDefinitions = defs; return this; }
        public Builder withFamilyPriorities(Int2IntMap priorities) { this.familyPriorities = priorities; return this; }

        private void finalizeSoAStructures() {
            int numUniqueCombinations = getUniqueCombinationCount();

            predicateCounts = new int[numUniqueCombinations];
            priorities = new int[numUniqueCombinations];
            ruleCodes = new String[numUniqueCombinations];
            combinationToPredicateIds = new IntList[numUniqueCombinations];

            for (int i = 0; i < numUniqueCombinations; i++) {
                IntList pIds = idToCombinationMap.get(i);
                if (pIds != null) {
                    predicateCounts[i] = pIds.size();
                    combinationToPredicateIds[i] = pIds;
                }
                if (ruleDefinitions != null && i < ruleDefinitions.length && ruleDefinitions[i] != null) {
                    priorities[i] = ruleDefinitions[i].priority();
                    ruleCodes[i] = ruleDefinitions[i].ruleCode();
                }
            }

            if (this.uniquePredicates == null && predicateRegistry != null) {
                this.uniquePredicates = new Predicate[predicateRegistry.size()];
                for (Object2IntMap.Entry<Predicate> entry : predicateRegistry.object2IntEntrySet()) {
                    this.uniquePredicates[entry.getIntValue()] = entry.getKey();
                }
            }

            if (uniquePredicates != null) {
                sortedPredicates = new ArrayList<>(List.of(uniquePredicates));
                sortedPredicates.sort(Comparator.comparing(Predicate::weight));
            } else {
                sortedPredicates = new ArrayList<>();
            }

            fieldMinWeights.defaultReturnValue(Float.MAX_VALUE);
            for (Predicate p : sortedPredicates) {
                if (!fieldMinWeights.containsKey(p.fieldId())) {
                    fieldMinWeights.put(p.fieldId(), p.weight());
                }
            }
        }

        public EngineModel build() {
            finalizeSoAStructures();
            return new EngineModel(this);
        }
    }

    public record EngineStats(
            int totalRules,
            int totalPredicates,
            long compilationTimeNanos,
            Map<String, Object> metadata
    ) {}

    public enum SelectionStrategy {
        ALL_MATCHES,
        FIRST_MATCH,
        MAX_PRIORITY_PER_FAMILY
    }
}