/*
 * Copyright (c) 2025 Helios Rule Engine
 * Licensed under the Apache License, Version 2.0
 */
package com.helios.ruleengine.runtime.model;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.helios.ruleengine.api.model.EngineStats;
import com.helios.ruleengine.api.model.Predicate;
import com.helios.ruleengine.api.model.RuleDefinition;
import com.helios.ruleengine.api.model.SelectionStrategy;
import it.unimi.dsi.fastutil.ints.*;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import org.roaringbitmap.RoaringBitmap;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The compiled, executable representation of a ruleset.
 *
 * This class holds all the data structures required for high-performance
 * rule evaluation, including dictionaries, predicate arrays, and the
 * inverted index. It is designed to be immutable and thread-safe.
 *
 * It uses a Structure-of-Arrays (SoA) data layout for combination metadata
 * (predicate counts, priorities, rule codes) to improve memory locality
 * and cache performance during evaluation.
 */
public final class EngineModel implements Serializable {
    private static final long serialVersionUID = 1L;

    // Defines the maximum size of the shared eligible predicate cache
    private static final int ELIGIBLE_PREDICATE_CACHE_SIZE = 10_000;

    // --- Core Data Structures ---
    private final Dictionary fieldDictionary;
    private final Dictionary valueDictionary;
    private final Predicate[] uniquePredicates; // All unique predicates, indexed by ID
    private final Int2ObjectMap<RoaringBitmap> invertedIndex; // Map[predicateId -> Bitmap(combinationIds)]

    // --- Rule & Combination Data (Structure-of-Arrays) ---
    private final int[] predicateCounts; // Map[combinationId -> count]
    private final int[] priorities; // Map[combinationId -> priority (of first rule)]
    private final String[] ruleCodes; // Map[combinationId -> ruleCode (of first rule)]
    private final IntList[] combinationToPredicateIds; // Map[combinationId -> List[predicateId]]

    // --- Deduplication & Multi-Rule Mapping ---
    // These maps store *all* rules associated with a single combination ID,
    // which is crucial for handling deduplication where one combination
    // satisfies multiple logical rules.
    private final List<String>[] combinationRuleCodes;
    private final List<Integer>[] combinationPriorities;

    // --- Optimization & Metadata ---
    private final EngineStats stats;
    private final List<Predicate> sortedPredicates; // All predicates, sorted by weight (cheapest first)
    private final Int2FloatMap fieldMinWeights; // Map[fieldId -> minimum weight (for early out)]
    private final SelectionStrategy selectionStrategy;
    private final Int2ObjectMap<List<Predicate>> fieldToPredicates; // Map[fieldId -> List[Predicate]]

    // --- Lookups & Caches ---
    private final Object2IntMap<PredicateKey> predicateKeyToId; // Lookup for predicate -> ID

    /**
     * A shared, thread-safe cache for evaluation results.
     * This cache maps a bitmap of "base conditions" (simple field-value matches)
     * to the set of "eligible predicate IDs" that pass evaluation.
     *
     * It is 'transient' so it is not serialized with the model.
     * It is re-initialized on-demand after deserialization.
     */
    private final transient com.github.benmanes.caffeine.cache.Cache<RoaringBitmap, IntSet> eligiblePredicateSetCache;

    // --- Deprecated / Legacy ---
    // Kept for backward compatibility or internal wiring
    private final RuleDefinition[] ruleDefinitions;
    private final Int2IntMap familyPriorities;

    // --- UI Integration & Reverse Lookups ---
    // These maps provide efficient reverse lookups for UI introspection
    // and debugging. They are built during compilation and have minimal
    // memory overhead (~1-3% of total model size).
    private final java.util.Map<String, java.util.Set<Integer>> ruleCodeToCombinationIds;
    private final Int2ObjectMap<java.util.Set<String>> predicateIdToRuleCodes;
    private final java.util.Map<String, com.helios.ruleengine.api.model.RuleMetadata> ruleMetadata;

    /**
     * Canonical key for predicate deduplication that ignores weight/selectivity.
     * This allows two predicates that are logically identical
     * (e.g., {field=A, op=EQ, val=B}) but were generated from different rules
     * with different selectivity profiles to be recognized as the *same*
     * predicate, sharing a single predicate ID.
     */
    static record PredicateKey(
            int fieldId,
            Predicate.Operator operator,
            Object value,
            Pattern pattern) implements Serializable {
        static PredicateKey from(Predicate p) {
            return new PredicateKey(p.fieldId(), p.operator(), p.value(), p.pattern());
        }
    }

    private EngineModel(Builder builder) {
        this.fieldDictionary = builder.fieldDictionary;
        this.valueDictionary = builder.valueDictionary;
        this.uniquePredicates = builder.uniquePredicates;
        this.invertedIndex = builder.invertedIndex;
        this.stats = builder.stats;
        this.sortedPredicates = builder.sortedPredicates;
        this.fieldMinWeights = builder.fieldMinWeights;
        this.selectionStrategy = builder.selectionStrategy;
        this.ruleDefinitions = builder.ruleDefinitions; // Legacy
        this.familyPriorities = builder.familyPriorities; // Legacy
        this.fieldToPredicates = builder.fieldToPredicates;

        // SoA data
        this.predicateCounts = builder.predicateCounts;
        this.priorities = builder.priorities;
        this.ruleCodes = builder.ruleCodes;
        this.combinationToPredicateIds = builder.combinationToPredicateIds;

        // Multi-rule mapping data
        this.combinationRuleCodes = builder.combinationRuleCodes;
        this.combinationPriorities = builder.combinationPriorities;

        // UI Integration reverse lookups (NEW)
        this.ruleCodeToCombinationIds = builder.ruleCodeToCombinationIds;
        this.predicateIdToRuleCodes = builder.predicateIdToRuleCodes;
        this.ruleMetadata = builder.ruleMetadata;

        // Build predicate key lookup map
        this.predicateKeyToId = new Object2IntOpenHashMap<>();
        this.predicateKeyToId.defaultReturnValue(-1);
        if (uniquePredicates != null) {
            for (int i = 0; i < uniquePredicates.length; i++) {
                PredicateKey key = PredicateKey.from(uniquePredicates[i]);
                this.predicateKeyToId.put(key, i);
            }
        }

        // Initialize the shared, thread-safe cache
        this.eligiblePredicateSetCache = Caffeine.newBuilder()
                .maximumSize(ELIGIBLE_PREDICATE_CACHE_SIZE)
                .build();
    }

    /**
     * Gets the shared, model-specific cache for eligible predicate sets.
     * This cache is thread-safe and shared across all RuleEvaluator instances
     * using this model.
     * 
     * @return The Caffeine cache instance.
     */
    public com.github.benmanes.caffeine.cache.Cache<RoaringBitmap, IntSet> getEligiblePredicateSetCache() {
        return eligiblePredicateSetCache;
    }

    public long getEligiblePredicateCacheMaxSize() {
        return ELIGIBLE_PREDICATE_CACHE_SIZE;
    }

    // --- Public Accessors ---

    /**
     * @return The total number of unique combinations (physical rules) in the
     *         model.
     */
    public int getNumRules() {
        return predicateCounts.length;
    }

    /**
     * @return The dictionary used for encoding field names.
     */
    public Dictionary getFieldDictionary() {
        return fieldDictionary;
    }

    /**
     * @return The dictionary used for encoding string values.
     */
    public Dictionary getValueDictionary() {
        return valueDictionary;
    }

    /**
     * @return The array of all unique predicates in the system.
     */
    public Predicate[] getUniquePredicates() {
        return uniquePredicates;
    }

    /**
     * @return The inverted index mapping predicate IDs to affected combination IDs.
     */
    public Int2ObjectMap<RoaringBitmap> getInvertedIndex() {
        return invertedIndex;
    }

    /**
     * @return Statistics about the compiled model (e.g., deduplication rate).
     */
    public EngineStats getStats() {
        return stats;
    }

    /**
     * @return All predicates sorted by evaluation cost (cheapest first).
     */
    public List<Predicate> getSortedPredicates() {
        return sortedPredicates;
    }

    /**
     * @param fieldId The dictionary ID of the field.
     * @return The minimum weight of any predicate associated with this field.
     */
    public float getFieldMinWeight(int fieldId) {
        return fieldMinWeights.get(fieldId);
    }

    /**
     * @return The strategy used to select which rules to return when multiple
     *         match.
     */
    public SelectionStrategy getSelectionStrategy() {
        return selectionStrategy;
    }

    /**
     * @return Legacy rule definitions (deprecated).
     */
    public RuleDefinition[] getRuleDefinitions() {
        return ruleDefinitions;
    }

    /**
     * @return Legacy family priorities (deprecated).
     */
    public Int2IntMap getFamilyPriorities() {
        return familyPriorities;
    }

    /**
     * @return Map of field ID to list of predicates for that field.
     */
    public Int2ObjectMap<List<Predicate>> getFieldToPredicates() {
        return fieldToPredicates;
    }

    /**
     * Retrieves a predicate by its unique ID.
     *
     * @param id The predicate ID.
     * @return The Predicate object, or null if ID is invalid.
     */
    public Predicate getPredicate(int id) {
        return (id >= 0 && id < uniquePredicates.length) ? uniquePredicates[id] : null;
    }

    /**
     * Get the ID of a predicate.
     * Uses a canonical key (without weight/selectivity) for lookup.
     *
     * @param p The predicate to look up.
     * @return The predicate ID, or -1 if not found.
     */
    public int getPredicateId(Predicate p) {
        PredicateKey key = PredicateKey.from(p);
        return predicateKeyToId.getInt(key);
    }

    // --- SoA Data Accessors ---

    /**
     * @return Array of predicate counts per combination.
     */
    public int[] getPredicateCounts() {
        return predicateCounts;
    }

    /**
     * @return Array of priorities per combination.
     */
    public int[] getPriorities() {
        return priorities;
    }

    /**
     * @param combinationId The combination ID.
     * @return List of predicate IDs required for this combination.
     */
    public IntList getCombinationPredicateIds(int combinationId) {
        return combinationToPredicateIds[combinationId];
    }

    // Backward compatible - returns *first* rule code
    public String getCombinationRuleCode(int combinationId) {
        return ruleCodes[combinationId];
    }

    public int getCombinationPriority(int combinationId) {
        return priorities[combinationId];
    }

    public int getCombinationPredicateCount(int combinationId) {
        return predicateCounts[combinationId];
    }

    /**
     * Gets the complete list of rule codes associated with a combination ID.
     * This is necessary for ALL_MATCHES strategies where a combination
     * was deduplicated across multiple rules.
     *
     * @param combinationId The combination ID.
     * @return List of rule codes.
     */
    public List<String> getCombinationRuleCodes(int combinationId) {
        if (combinationRuleCodes != null && combinationId < combinationRuleCodes.length
                && combinationRuleCodes[combinationId] != null) {
            return combinationRuleCodes[combinationId];
        }
        // Fallback for older models or single-rule combinations
        return List.of(ruleCodes[combinationId]);
    }

    /**
     * Gets the complete list of priorities associated with a combination ID,
     * corresponding to the rule codes from `getCombinationRuleCodes`.
     *
     * @param combinationId The combination ID.
     * @return List of priorities.
     */
    public List<Integer> getCombinationPrioritiesAll(int combinationId) {
        if (combinationPriorities != null && combinationId < combinationPriorities.length
                && combinationPriorities[combinationId] != null) {
            return combinationPriorities[combinationId];
        }
        // Fallback for older models or single-rule combinations
        return List.of(priorities[combinationId]);
    }

    // --- UI Integration & Reverse Lookup Accessors (NEW) ---

    /**
     * Gets all combination IDs associated with a logical rule.
     * Useful for UI to show which physical combinations a rule expands to.
     *
     * @param ruleCode The rule code
     * @return Set of combination IDs, empty set if rule not found
     */
    public java.util.Set<Integer> getCombinationIdsForRule(String ruleCode) {
        if (ruleCodeToCombinationIds == null) {
            return java.util.Set.of();
        }
        return ruleCodeToCombinationIds.getOrDefault(ruleCode, java.util.Set.of());
    }

    /**
     * Gets all rule codes that use a specific predicate.
     * Useful for UI to show predicate usage heatmap and dependency analysis.
     *
     * @param predicateId The predicate ID
     * @return Set of rule codes, empty set if predicate not found
     */
    public java.util.Set<String> getRulesUsingPredicate(int predicateId) {
        if (predicateIdToRuleCodes == null) {
            return java.util.Set.of();
        }
        return predicateIdToRuleCodes.getOrDefault(predicateId, java.util.Set.of());
    }

    /**
     * Gets rich metadata for a specific rule.
     *
     * @param ruleCode The rule code
     * @return RuleMetadata instance, or null if not found
     */
    public com.helios.ruleengine.api.model.RuleMetadata getRuleMetadata(String ruleCode) {
        if (ruleMetadata == null) {
            return null;
        }
        return ruleMetadata.get(ruleCode);
    }

    /**
     * Gets all rule metadata in the model.
     * Useful for UI to list all rules with their metadata.
     *
     * @return Collection of all RuleMetadata, empty if none
     */
    public java.util.Collection<com.helios.ruleengine.api.model.RuleMetadata> getAllRuleMetadata() {
        if (ruleMetadata == null) {
            return java.util.List.of();
        }
        return ruleMetadata.values();
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
        RuleDefinition[] ruleDefinitions; // Legacy
        Int2IntMap familyPriorities; // Legacy
        final Int2ObjectMap<List<Predicate>> fieldToPredicates = new Int2ObjectOpenHashMap<>();

        // --- UI Integration Reverse Lookups (NEW) ---
        final java.util.Map<String, java.util.Set<Integer>> ruleCodeToCombinationIds = new java.util.HashMap<>();
        final Int2ObjectMap<java.util.Set<String>> predicateIdToRuleCodes = new Int2ObjectOpenHashMap<>();
        final java.util.Map<String, com.helios.ruleengine.api.model.RuleMetadata> ruleMetadata = new java.util.HashMap<>();

        // --- Internal Build-Time Structures ---

        // Map[PredicateKey -> predicateId]
        private final Object2IntMap<PredicateKey> predicateIdMap = new Object2IntOpenHashMap<>();
        private final List<Predicate> predicateList = new ArrayList<>();

        // Map[List<predicateId> -> combinationId]
        private final Object2IntMap<IntList> combinationToIdMap = new Object2IntOpenHashMap<>();
        // Map[combinationId -> List<predicateId>]
        private final Int2ObjectMap<IntList> idToCombinationMap = new Int2ObjectOpenHashMap<>();
        private int totalExpandedCombinations = 0;

        // --- SoA Build-Time Arrays ---
        int[] predicateCounts;
        int[] priorities;
        String[] ruleCodes;
        IntList[] combinationToPredicateIds;

        // Map[combinationId -> List[ruleCode]]
        List<String>[] combinationRuleCodes;
        // Map[combinationId -> List[priority]]
        List<Integer>[] combinationPriorities;

        @SuppressWarnings("unchecked")
        public Builder() {
            combinationToIdMap.defaultReturnValue(-1);
            predicateIdMap.defaultReturnValue(-1);
        }

        /**
         * Registers a predicate and returns its unique ID.
         *
         * This method uses a `PredicateKey` (which ignores weight/selectivity)
         * for deduplication. This ensures that logically identical predicates
         * from different rules (which might have different calculated weights)
         * are all mapped to the *same* predicate ID.
         *
         * @param predicate The predicate to register.
         * @return The unique, persistent ID for this predicate.
         */
        public int registerPredicate(Predicate predicate) {
            PredicateKey key = PredicateKey.from(predicate);
            int existingId = predicateIdMap.getInt(key);
            if (existingId != predicateIdMap.defaultReturnValue()) {
                // Already registered - return existing ID
                return existingId;
            }

            // New predicate - register it
            int id = predicateList.size();
            predicateIdMap.put(key, id);
            predicateList.add(predicate);

            // Also add to field mapping
            fieldToPredicates.computeIfAbsent(predicate.fieldId(), k -> new ArrayList<>()).add(predicate);

            return id;
        }

        /**
         * Registers a combination (a sorted list of predicate IDs) and
         * returns its unique ID.
         *
         * This method is the core of combination deduplication.
         *
         * @param predicateIds A *sorted* list of predicate IDs.
         * @return The unique, persistent ID for this combination.
         */
        public int registerCombination(IntList predicateIds) {
            totalExpandedCombinations++; // Count *before* deduplication
            int combinationId = combinationToIdMap.getInt(predicateIds);

            if (combinationId == -1) {
                // This is a new, unique combination
                combinationId = combinationToIdMap.size();
                // Store new copies to ensure immutability
                combinationToIdMap.put(new IntArrayList(predicateIds), combinationId);
                idToCombinationMap.put(combinationId, new IntArrayList(predicateIds));
            }
            return combinationId;
        }

        /**
         * Maps a logical rule (by its code, priority, etc.) to a specific
         * physical combination ID.
         *
         * This handles the M:N mapping between logical rules and
         * unique combinations.
         */
        @SuppressWarnings("unchecked")
        public void addLogicalRuleMapping(String ruleCode, Integer priority, String description, int combinationId) {
            // Ensure the multi-rule tracking arrays are initialized and large enough
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

            // Add the rule code if it's not already mapped to this combination
            List<String> codes = combinationRuleCodes[combinationId];
            if (!codes.contains(ruleCode)) {
                codes.add(ruleCode);
                combinationPriorities[combinationId].add(priority != null ? priority : 0);
            }

            // --- Populate UI Integration reverse lookups (NEW) ---
            // Track rule → combination mapping
            ruleCodeToCombinationIds
                .computeIfAbsent(ruleCode, k -> new java.util.HashSet<>())
                .add(combinationId);

            // Track predicate → rule mapping
            IntList predicateIds = idToCombinationMap.get(combinationId);
            if (predicateIds != null) {
                for (int predId : predicateIds) {
                    predicateIdToRuleCodes
                        .computeIfAbsent(predId, k -> new java.util.HashSet<>())
                        .add(ruleCode);
                }
            }
            // --- End UI Integration ---

            // --- Populate legacy fields for backward compatibility ---
            if (ruleDefinitions == null) {
                int initialSize = Math.max(100, combinationToIdMap.size() + 50);
                ruleDefinitions = new RuleDefinition[initialSize];
            }
            if (combinationId >= ruleDefinitions.length) {
                RuleDefinition[] expanded = new RuleDefinition[combinationId + 50];
                System.arraycopy(ruleDefinitions, 0, expanded, 0, ruleDefinitions.length);
                ruleDefinitions = expanded;
            }
            // Only store the *first* rule definition for this combination
            if (ruleDefinitions[combinationId] == null) {
                ruleDefinitions[combinationId] = new RuleDefinition(
                        ruleCode,
                        new ArrayList<>(), // Conditions are not preserved here
                        priority != null ? priority : 0,
                        description != null ? description : "",
                        true);
            }
            // --- End Legacy ---
        }

        /**
         * Registers rule metadata for UI integration.
         * This should be called during compilation for each rule.
         *
         * @param metadata The rule metadata
         */
        public void addRuleMetadata(com.helios.ruleengine.api.model.RuleMetadata metadata) {
            if (metadata != null && metadata.ruleCode() != null) {
                ruleMetadata.put(metadata.ruleCode(), metadata);
            }
        }

        public int getTotalExpandedCombinations() {
            return totalExpandedCombinations;
        }

        public int getUniqueCombinationCount() {
            return combinationToIdMap.size();
        }

        public int getPredicateCount() {
            return predicateList.size();
        }

        public Builder withFieldDictionary(Dictionary dictionary) {
            this.fieldDictionary = dictionary;
            return this;
        }

        public Builder withValueDictionary(Dictionary dictionary) {
            this.valueDictionary = dictionary;
            return this;
        }

        public Builder withStats(EngineStats stats) {
            this.stats = stats;
            return this;
        }

        public Builder withSelectionStrategy(SelectionStrategy strategy) {
            this.selectionStrategy = strategy;
            return this;
        }

        /**
         * Finalizes the build, converting internal lists into optimized
         * arrays (Structure-of-Arrays) for the final EngineModel.
         */
        private void finalizeSoAStructures() {
            int numCombinations = getUniqueCombinationCount();

            // Initialize SoA arrays
            predicateCounts = new int[numCombinations];
            priorities = new int[numCombinations];
            ruleCodes = new String[numCombinations];
            combinationToPredicateIds = new IntList[numCombinations];

            // Populate SoA arrays from build-time maps
            for (int i = 0; i < numCombinations; i++) {
                IntList predicateIds = idToCombinationMap.get(i);
                if (predicateIds != null) {
                    combinationToPredicateIds[i] = new IntArrayList(predicateIds);
                    predicateCounts[i] = predicateIds.size();
                } else {
                    combinationToPredicateIds[i] = new IntArrayList();
                    predicateCounts[i] = 0;
                }

                // Populate rule metadata - use *first* rule for backward compatibility
                if (combinationRuleCodes != null && i < combinationRuleCodes.length &&
                        combinationRuleCodes[i] != null && !combinationRuleCodes[i].isEmpty()) {
                    ruleCodes[i] = combinationRuleCodes[i].get(0);
                    priorities[i] = combinationPriorities[i].get(0);
                } else if (ruleDefinitions != null && i < ruleDefinitions.length && ruleDefinitions[i] != null) {
                    // Fallback to legacy structure
                    priorities[i] = ruleDefinitions[i].priority();
                    ruleCodes[i] = ruleDefinitions[i].ruleCode();
                } else {
                    // Should not happen, but safeguard
                    priorities[i] = 0;
                    ruleCodes[i] = "UNKNOWN_RULE_" + i;
                }
            }

            // Finalize the unique predicate array
            if (this.uniquePredicates == null) {
                this.uniquePredicates = predicateList.toArray(new Predicate[0]);
            }

            // Sort predicates by weight for optimal evaluation order
            if (uniquePredicates != null && uniquePredicates.length > 0) {
                sortedPredicates = new ArrayList<>(List.of(uniquePredicates));
                // Sort by weight, ascending (cheapest to evaluate first)
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
         * Builds the inverted index (predicateId -> combinationIds).
         * This is the core data structure for runtime evaluation.
         */
        private void buildInvertedIndex() {
            int numCombinations = getUniqueCombinationCount();

            for (int combinationId = 0; combinationId < numCombinations; combinationId++) {
                IntList predicateIds = idToCombinationMap.get(combinationId);
                if (predicateIds == null)
                    continue;

                // For each predicate in the combination, add this combination's
                // ID to that predicate's bitmap in the inverted index.
                for (int predId : predicateIds) {
                    invertedIndex.computeIfAbsent(predId, k -> new RoaringBitmap()).add(combinationId);
                }
            }
        }

        /**
         * Builds the final EngineModel with all optimizations applied.
         */
        public EngineModel build() {
            // Phase 1: Finalize Structure-of-Arrays layout
            finalizeSoAStructures();

            // Phase 2: Build inverted index
            buildInvertedIndex();

            // Phase 3: Validate model integrity
            validate();

            // Phase 4: Construct immutable EngineModel
            return new EngineModel(this);
        }

        /**
         * Validates the model for common integrity issues before finalizing.
         */
        private void validate() {
            int numCombinations = getUniqueCombinationCount();
            if (numCombinations == 0 && getPredicateCount() == 0) {
                // Empty model is valid
                return;
            }

            // Check 1: Inverted index must be non-empty for non-trivial models
            if (numCombinations > 0 && invertedIndex.isEmpty()) {
                throw new IllegalStateException(
                        "Inverted index is empty but model has " + numCombinations +
                                " combinations. This indicates a build error.");
            }

            // Check 2: All combinations must have at least one predicate
            // (Empty-condition rules are not supported by this model)
            for (int i = 0; i < numCombinations; i++) {
                if (predicateCounts[i] == 0) {
                    throw new IllegalStateException(
                            "Combination " + i + " has zero predicates. " +
                                    "This should not happen after compilation.");
                }
            }

            // Check 3: Rule codes must be non-null
            for (int i = 0; i < numCombinations; i++) {
                if (ruleCodes[i] == null) {
                    throw new IllegalStateException(
                            "Combination " + i + " has null rule code. " +
                                    "This indicates a registration error.");
                }
            }
        }
    }
}