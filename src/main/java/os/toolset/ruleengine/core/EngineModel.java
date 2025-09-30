package os.toolset.ruleengine.core;

import it.unimi.dsi.fastutil.ints.*;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.roaringbitmap.RoaringBitmap;
import os.toolset.ruleengine.model.Predicate;
import os.toolset.ruleengine.model.Rule;

import java.util.*;
import java.util.logging.Logger;

/**
 * Phase 4: High-performance engine model using Structure of Arrays (SoA) layout
 * based on deduplicated predicate combinations.
 */
public final class EngineModel {
    private static final Logger logger = Logger.getLogger(EngineModel.class.getName());

    // ========== DICTIONARIES ==========
    private final Dictionary fieldDictionary;
    private final Dictionary valueDictionary;

    // ========== PREDICATE & COMBINATION STORAGE ==========
    private final Object2IntMap<Predicate> predicateRegistry;
    private final Int2ObjectMap<Predicate> predicateLookup;
    private final Int2ObjectMap<List<Predicate>> fieldToPredicates;
    private final Int2ObjectMap<IntList> combinationIdToPredicateIds;

    // ========== INVERTED INDEX ==========
    private final Int2ObjectMap<RoaringBitmap> invertedIndex;

    // ========== STRUCTURE OF ARRAYS (SoA) - Indexed by combinationId ==========
    private final int numCombinations;
    private final int[] predicateCounts;
    private final IntList[] combinationToPredicateIds;

    // ========== LOGICAL RULE MAPPING ==========
    private final Object2ObjectMap<String, IntList> logicalRuleToCombinationIds;
    private final Int2ObjectMap<String> combinationIdToLogicalRuleCode;
    private final Int2IntMap combinationIdToPriority;


    // Statistics
    private final EngineStats stats;

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
    }

    // ========== PUBLIC ACCESSORS ==========
    public int getNumRules() { return numCombinations; } // Now represents unique combinations
    public Dictionary getFieldDictionary() { return fieldDictionary; }
    public Dictionary getValueDictionary() { return valueDictionary; }
    public Object2IntMap<Predicate> getPredicateRegistry() { return predicateRegistry; }
    public Int2ObjectMap<RoaringBitmap> getInvertedIndex() { return invertedIndex; }
    public Int2ObjectMap<List<Predicate>> getFieldToPredicates() { return fieldToPredicates; }
    public Predicate getPredicate(int id) { return predicateLookup.get(id); }
    public int getPredicateId(Predicate p) { return predicateRegistry.getInt(p); }
    public EngineStats getStats() { return stats; }

    // Direct SoA accessors for hyper-optimized evaluation
    public int getCombinationPredicateCount(int combinationId) { return predicateCounts[combinationId]; }
    public String getCombinationRuleCode(int combinationId) { return combinationIdToLogicalRuleCode.get(combinationId); }
    public int getCombinationPriority(int combinationId) { return combinationIdToPriority.get(combinationId); }
    public IntList getCombinationPredicateIds(int combinationId) { return combinationToPredicateIds[combinationId]; }


    // ========== BUILDER ==========
    public static class Builder {
        Dictionary fieldDictionary;
        Dictionary valueDictionary;
        final Object2IntMap<Predicate> predicateRegistry = new Object2IntOpenHashMap<>();
        final Int2ObjectMap<Predicate> predicateLookup = new Int2ObjectOpenHashMap<>();
        final Int2ObjectMap<List<Predicate>> fieldToPredicates = new Int2ObjectOpenHashMap<>();
        final Int2ObjectMap<RoaringBitmap> invertedIndex = new Int2ObjectOpenHashMap<>();
        EngineStats stats;

        // Deduplication structures
        private final Object2IntMap<IntList> combinationToIdMap = new Object2IntOpenHashMap<>();
        final Int2ObjectMap<IntList> idToCombinationMap = new Int2ObjectOpenHashMap<>();
        private int totalExpandedCombinations = 0;

        // Logical rule mapping
        final Object2ObjectMap<String, IntList> logicalRuleToCombinationIds = new Object2ObjectOpenHashMap<>();
        final Int2ObjectMap<String> combinationIdToLogicalRuleCode = new Int2ObjectOpenHashMap<>();
        final Int2IntMap combinationIdToPriority = new Int2IntOpenHashMap();

        // SoA arrays (will be sized to unique combinations)
        int[] predicateCounts;
        IntList[] combinationToPredicateIds;

        public Builder() {
            combinationToIdMap.defaultReturnValue(-1);
        }

        public int registerPredicate(Predicate predicate) {
            // FIX: Explicitly type the lambda parameter 'p' to resolve ambiguity
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
                // When a new combination is found, add its predicates to the inverted index
                for (int predId : predicateIds) {
                    invertedIndex.computeIfAbsent(predId, k -> new RoaringBitmap()).add(combinationId);
                }
            }
            return combinationId;
        }

        public void addLogicalRuleMapping(String ruleCode, int priority, String description, int combinationId) {
            logicalRuleToCombinationIds.computeIfAbsent(ruleCode, k -> new IntArrayList()).add(combinationId);
            combinationIdToLogicalRuleCode.put(combinationId, ruleCode);
            // Store the highest priority for a given combination
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

            // Populate SoA arrays based on unique combinations
            predicateCounts = new int[numUniqueCombinations];
            combinationToPredicateIds = new IntList[numUniqueCombinations];

            for (int i = 0; i < numUniqueCombinations; i++) {
                IntList pIds = idToCombinationMap.get(i);
                predicateCounts[i] = pIds.size();
                combinationToPredicateIds[i] = pIds;
            }
        }

        public EngineModel build() {
            predicateRegistry.defaultReturnValue(-1);
            finalizeOptimizedStructures();
            return new EngineModel(this);
        }
    }

    public record EngineStats(
            int totalRules, // Now represents unique combinations
            int totalPredicates,
            long compilationTimeNanos,
            Map<String, Object> metadata
    ) {}
}