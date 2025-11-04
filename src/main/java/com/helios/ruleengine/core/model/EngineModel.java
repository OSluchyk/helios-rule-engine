/*
 * Copyright (c) 2025 Helios Rule Engine
 * Licensed under the Apache License, Version 2.0
 */
package com.helios.ruleengine.core.model;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.helios.ruleengine.model.Predicate;
import com.helios.ruleengine.model.RuleDefinition;
import it.unimi.dsi.fastutil.ints.*;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import org.roaringbitmap.RoaringBitmap;

import java.io.Serializable;
import java.util.*;
import java.util.regex.Pattern;

public final class EngineModel implements Serializable {
    private static final long serialVersionUID = 1L;

    // ✅ RECOMMENDATION 2 FIX: Define cache size constant here
    private static final int ELIGIBLE_PREDICATE_CACHE_SIZE = 10_000;

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

    private final int[] predicateCounts;
    private final int[] priorities;
    private final String[] ruleCodes;
    private final IntList[] combinationToPredicateIds;

    private final List<String>[] combinationRuleCodes;
    private final List<Integer>[] combinationPriorities;

    // Cache for predicate ID lookups
    private final Object2IntMap<PredicateKey> predicateKeyToId;

    /**
     * ✅ RECOMMENDATION 2 FIX
     * The eligiblePredicateSetCache is moved here from RuleEvaluator.
     * It is now tied to the EngineModel, not an evaluator instance.
     * It is marked 'transient' so it's not serialized with the model.
     * It is thread-safe (Caffeine) and shared by all evaluators.
     */
    private final transient com.github.benmanes.caffeine.cache.Cache<RoaringBitmap, IntSet> eligiblePredicateSetCache;


    public enum SelectionStrategy {
        ALL_MATCHES,
        MAX_PRIORITY_PER_FAMILY,
        FIRST_MATCH
    }

    public record EngineStats(
            int uniqueCombinations,
            int totalPredicates,
            long compilationTimeNanos,
            Map<String, Object> metadata
    ) implements Serializable {
    }

    /**
     * Canonical key for predicate deduplication that ignores weight/selectivity.
     * This allows predicates with the same field/operator/value but different weights
     * to be identified as the same predicate.
     */
    static record PredicateKey(
            int fieldId,
            Predicate.Operator operator,
            Object value,
            Pattern pattern
    ) implements Serializable {
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
        this.ruleDefinitions = builder.ruleDefinitions;
        this.familyPriorities = builder.familyPriorities;
        this.fieldToPredicates = builder.fieldToPredicates;

        this.predicateCounts = builder.predicateCounts;
        this.priorities = builder.priorities;
        this.ruleCodes = builder.ruleCodes;
        this.combinationToPredicateIds = builder.combinationToPredicateIds;

        this.combinationRuleCodes = builder.combinationRuleCodes;
        this.combinationPriorities = builder.combinationPriorities;

        // Build predicate key lookup map (handle null uniquePredicates)
        this.predicateKeyToId = new Object2IntOpenHashMap<>();
        this.predicateKeyToId.defaultReturnValue(-1);
        if (uniquePredicates != null) {
            for (int i = 0; i < uniquePredicates.length; i++) {
                PredicateKey key = PredicateKey.from(uniquePredicates[i]);
                this.predicateKeyToId.put(key, i);
            }
        }

        // ✅ RECOMMENDATION 2 FIX: Initialize the shared, thread-safe cache
        this.eligiblePredicateSetCache = Caffeine.newBuilder()
                .maximumSize(ELIGIBLE_PREDICATE_CACHE_SIZE)
                .build();
    }

    /**
     * Gets the shared, model-specific cache for eligible predicate sets.
     * This cache is thread-safe and shared across all RuleEvaluator instances
     * using this model.
     * @return The Caffeine cache instance.
     */
    public com.github.benmanes.caffeine.cache.Cache<RoaringBitmap, IntSet> getEligiblePredicateSetCache() {
        return eligiblePredicateSetCache;
    }

    /**
     * Getter for the cache size constant.
     */
    public long getEligiblePredicateCacheMaxSize() {
        return ELIGIBLE_PREDICATE_CACHE_SIZE;
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

    /**
     * Get the ID of a predicate.
     * Uses canonical key (without weight/selectivity) for lookup.
     */
    public int getPredicateId(Predicate p) {
        PredicateKey key = PredicateKey.from(p);
        return predicateKeyToId.getInt(key);
    }

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

        // FIX: Changed to use PredicateKey for deduplication
        private final Object2IntMap<PredicateKey> predicateIdMap = new Object2IntOpenHashMap<>();
        private final List<Predicate> predicateList = new ArrayList<>();

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

        @SuppressWarnings("unchecked")
        public Builder() {
            combinationToIdMap.defaultReturnValue(-1);
            predicateIdMap.defaultReturnValue(-1);
        }

        /**
         * Register a predicate and return its ID.
         * FIX: Uses PredicateKey (without weight/selectivity) for deduplication,
         * but preserves the actual Predicate with its weight/selectivity.
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

        private void finalizeSoAStructures() {
            int numCombinations = getUniqueCombinationCount();

            predicateCounts = new int[numCombinations];
            priorities = new int[numCombinations];
            ruleCodes = new String[numCombinations];
            combinationToPredicateIds = new IntList[numCombinations];

            for (int i = 0; i < numCombinations; i++) {
                IntList predicateIds = idToCombinationMap.get(i);
                if (predicateIds != null) {
                    combinationToPredicateIds[i] = new IntArrayList(predicateIds);
                    predicateCounts[i] = predicateIds.size();
                } else {
                    combinationToPredicateIds[i] = new IntArrayList();
                    predicateCounts[i] = 0;
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

            // FIX: Always initialize uniquePredicates array (even if empty)
            if (this.uniquePredicates == null) {
                if (!predicateList.isEmpty()) {
                    this.uniquePredicates = predicateList.toArray(new Predicate[0]);
                } else {
                    // Empty array instead of null
                    this.uniquePredicates = new Predicate[0];
                }
            }

            // Sort predicates by weight for optimal evaluation order
            if (uniquePredicates != null && uniquePredicates.length > 0) {
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

        private void buildInvertedIndex() {
            int numCombinations = getUniqueCombinationCount();

            for (int combinationId = 0; combinationId < numCombinations; combinationId++) {
                IntList predicateIds = idToCombinationMap.get(combinationId);
                if (predicateIds == null) continue;

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
                                " combinations. This indicates a build error."
                );
            }

            // Check 2: All combinations must have at least one predicate
            for (int i = 0; i < numCombinations; i++) {
                if (predicateCounts[i] == 0) {
                    throw new IllegalStateException(
                            "Combination " + i + " has zero predicates. " +
                                    "This should never happen after compilation."
                    );
                }
            }

            // Check 3: Rule codes must be non-null
            for (int i = 0; i < numCombinations; i++) {
                if (ruleCodes[i] == null) {
                    throw new IllegalStateException(
                            "Combination " + i + " has null rule code. " +
                                    "This indicates a registration error."
                    );
                }
            }
        }
    }
}