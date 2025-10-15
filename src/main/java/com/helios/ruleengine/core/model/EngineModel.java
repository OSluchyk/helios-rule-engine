package com.helios.ruleengine.core.model;

import com.helios.ruleengine.model.Predicate;
import com.helios.ruleengine.model.RuleDefinition;
import it.unimi.dsi.fastutil.floats.Float2FloatMap;
import it.unimi.dsi.fastutil.floats.Float2FloatOpenHashMap;
import it.unimi.dsi.fastutil.ints.*;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import org.roaringbitmap.RoaringBitmap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * P4-A FIX + DEDUPLICATION FIX: The core data model for the rule engine.
 *
 * CRITICAL FIXES:
 * - Support multiple rule codes per combination (1:N mapping)
 * - Preserve all logical rule associations after IS_ANY_OF expansion
 * - Enable proper deduplication without losing rule metadata
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

    // --- NEW: Multiple rule codes per combination ---
    private final List<String>[] combinationRuleCodes;
    private final List<Integer>[] combinationPriorities;

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
        this.combinationRuleCodes = builder.combinationRuleCodes;
        this.combinationPriorities = builder.combinationPriorities;
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
    public Predicate getPredicate(int id) { return (id >= 0 && id < uniquePredicates.length) ?
            uniquePredicates[id] : null; }
    public int getPredicateId(Predicate p) { return predicateRegistry.getInt(p); }
    public Int2ObjectMap<List<Predicate>> getFieldToPredicates() { return fieldToPredicates; }


    // --- SoA Data Accessors ---

    public int[] getPredicateCounts() { return predicateCounts; }
    public int[] getPriorities() { return priorities; }
    public IntList getCombinationPredicateIds(int combinationId) { return combinationToPredicateIds[combinationId]; }

    // Backward compatible - returns first rule code
    public String getCombinationRuleCode(int combinationId) { return ruleCodes[combinationId]; }
    public int getCombinationPriority(int combinationId) { return priorities[combinationId]; }
    public int getCombinationPredicateCount(int combinationId) { return predicateCounts[combinationId]; }

    // NEW: Get all rule codes for a combination (for proper deduplication handling)
    public List<String> getCombinationRuleCodes(int combinationId) {
        if (combinationRuleCodes != null && combinationId < combinationRuleCodes.length
                && combinationRuleCodes[combinationId] != null) {
            return combinationRuleCodes[combinationId];
        }
        return List.of(ruleCodes[combinationId]);
    }

    public List<Integer> getCombinationPrioritiesAll(int combinationId) {
        if (combinationPriorities != null && combinationId < combinationPriorities.length
                && combinationPriorities[combinationId] != null) {
            return combinationPriorities[combinationId];
        }
        return List.of(priorities[combinationId]);
    }

    // NEW: Get the predicate IDs for this combination (for validation)
    public IntList getCombinationPredicateIdsForValidation(int combinationId) {
        return combinationToPredicateIds[combinationId];
    }

    public static class Builder {
        Dictionary fieldDictionary;
        Dictionary valueDictionary;
        Predicate[] uniquePredicates;
        final Int2ObjectMap<RoaringBitmap> invertedIndex = new Int2ObjectOpenHashMap<>();
        EngineStats stats;
        List<Predicate> sortedPredicates;
        final Int2FloatMap fieldMinWeights = new Int2FloatOpenHashMap();
        SelectionStrategy selectionStrategy = SelectionStrategy.FIRST_MATCH;
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

        // NEW: Store ALL rule codes and their predicates per combination
        List<String>[] combinationRuleCodes;
        List<Integer>[] combinationPriorities;
        // NEW: Track which predicates each rule-combination pair has
        Map<String, IntSet>[] combinationRulePredicates;

        @SuppressWarnings("unchecked")
        public Builder() {
            combinationToIdMap.defaultReturnValue(-1);
        }

        public int registerPredicate(Predicate predicate) {
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

        @SuppressWarnings("unchecked")
        public void addLogicalRuleMapping(String ruleCode, Integer priority, String description, int combinationId) {
            // Restore multi-rule tracking
            if (combinationRuleCodes == null) {
                int initialSize = Math.max(100, combinationToIdMap.size() + 50);
                combinationRuleCodes = (List<String>[]) new List<?>[initialSize];
                combinationPriorities = (List<Integer>[]) new List<?>[initialSize];
            }

            if (combinationId >= combinationRuleCodes.length) {
                int newSize = combinationId + 50;
                List<String>[] expandedCodes = (List<String>[]) new List<?>[newSize];
                List<Integer>[] expandedPriorities = (List<Integer>[]) new List<?>[newSize];
                System.arraycopy(combinationRuleCodes, 0, expandedCodes, 0, combinationRuleCodes.length);
                System.arraycopy(combinationPriorities, 0, expandedPriorities, 0, combinationPriorities.length);
                combinationRuleCodes = expandedCodes;
                combinationPriorities = expandedPriorities;
            }

            if (combinationRuleCodes[combinationId] == null) {
                combinationRuleCodes[combinationId] = new ArrayList<>();
                combinationPriorities[combinationId] = new ArrayList<>();
            }

            // Add if not already present
            List<String> codes = combinationRuleCodes[combinationId];
            if (!codes.contains(ruleCode)) {
                codes.add(ruleCode);
                combinationPriorities[combinationId].add(priority != null ? priority : 0);
            }

            // Backward compatibility
            if (ruleDefinitions == null) {
                int initialSize = Math.max(100, combinationToIdMap.size() + 50);
                ruleDefinitions = new RuleDefinition[initialSize];
            }

            if (combinationId >= ruleDefinitions.length) {
                RuleDefinition[] expanded = new RuleDefinition[combinationId + 50];
                System.arraycopy(ruleDefinitions, 0, expanded, 0, ruleDefinitions.length);
                ruleDefinitions = expanded;
            }

            if (ruleDefinitions[combinationId] == null) {
                ruleDefinitions[combinationId] = new RuleDefinition(
                        ruleCode,
                        new ArrayList<>(),
                        priority != null ? priority : 0,
                        description != null ? description : "",
                        true
                );
            }
        }

        /**
         * Builds the inverted index: predicateId → RoaringBitmap of rule combination IDs.
         */
        public void buildInvertedIndex() {
            for (Object2IntMap.Entry<IntList> entry : combinationToIdMap.object2IntEntrySet()) {
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

        /**
         * Finalizes the Structure-of-Arrays (SoA) layout for cache-optimal runtime performance.
         */
        @SuppressWarnings("unchecked")
        private void finalizeSoAStructures() {
            int numUniqueCombinations = getUniqueCombinationCount();

            // Allocate SoA arrays with exact size needed
            predicateCounts = new int[numUniqueCombinations];
            priorities = new int[numUniqueCombinations];
            ruleCodes = new String[numUniqueCombinations];
            combinationToPredicateIds = new IntList[numUniqueCombinations];

            // Populate SoA arrays from combination mappings
            for (int i = 0; i < numUniqueCombinations; i++) {
                // Populate predicate data
                IntList pIds = idToCombinationMap.get(i);
                if (pIds != null) {
                    predicateCounts[i] = pIds.size();
                    combinationToPredicateIds[i] = pIds;
                } else {
                    predicateCounts[i] = 0;
                    combinationToPredicateIds[i] = new IntArrayList();
                }

                // Populate rule metadata - use first rule for backward compatibility
                if (combinationRuleCodes != null && i < combinationRuleCodes.length &&
                        combinationRuleCodes[i] != null && !combinationRuleCodes[i].isEmpty()) {
                    ruleCodes[i] = combinationRuleCodes[i].get(0);
                    priorities[i] = combinationPriorities[i].get(0);
                } else if (ruleDefinitions != null && i < ruleDefinitions.length && ruleDefinitions[i] != null) {
                    priorities[i] = ruleDefinitions[i].priority();
                    ruleCodes[i] = ruleDefinitions[i].ruleCode();
                } else {
                    priorities[i] = 0;
                    ruleCodes[i] = "UNKNOWN_RULE_" + i;
                }
            }

            // Build unique predicates array if not already set
            if (this.uniquePredicates == null && predicateRegistry != null) {
                this.uniquePredicates = new Predicate[predicateRegistry.size()];
                for (Object2IntMap.Entry<Predicate> entry : predicateRegistry.object2IntEntrySet()) {
                    this.uniquePredicates[entry.getIntValue()] = entry.getKey();
                }
            }

            // Sort predicates by weight for optimal evaluation order
            if (uniquePredicates != null) {
                sortedPredicates = new ArrayList<>(List.of(uniquePredicates));
                sortedPredicates.sort(Comparator.comparing(Predicate::weight));
            } else {
                sortedPredicates = new ArrayList<>();
            }

            // Build field minimum weights map for early termination
            fieldMinWeights.defaultReturnValue(Float.MAX_VALUE);
            for (Predicate p : sortedPredicates) {
                if (!fieldMinWeights.containsKey(p.fieldId())) {
                    fieldMinWeights.put(p.fieldId(), p.weight());
                }
            }
        }

        /**
         * Builds the final EngineModel with all optimizations applied.
         */
        public EngineModel build() {
            // Phase 1: Finalize Structure-of-Arrays layout
            finalizeSoAStructures();

            // Phase 2: Build inverted index (CRITICAL - was missing!)
            buildInvertedIndex();

            // Phase 3: Validate model integrity
            validate();

            // Phase 4: Construct immutable EngineModel
            return new EngineModel(this);
        }

        /**
         * Validates the model for common integrity issues.
         */
        private void validate() {
            int numCombinations = getUniqueCombinationCount();

            // Check 1: Inverted index must be non-empty for non-trivial models
            if (numCombinations > 0 && invertedIndex.isEmpty()) {
                throw new IllegalStateException(
                        "Inverted index is empty but model has " + numCombinations +
                                " combinations. buildInvertedIndex() was not called."
                );
            }

            // Check 2: SoA arrays must be properly sized
            if (ruleCodes != null && ruleCodes.length != numCombinations) {
                throw new IllegalStateException(
                        "ruleCodes array size mismatch: expected " + numCombinations +
                                ", got " + ruleCodes.length
                );
            }

            if (priorities != null && priorities.length != numCombinations) {
                throw new IllegalStateException(
                        "priorities array size mismatch: expected " + numCombinations +
                                ", got " + priorities.length
                );
            }
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