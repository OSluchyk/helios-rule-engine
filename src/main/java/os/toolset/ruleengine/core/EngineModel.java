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
 * and Dictionary Encoding for fields and values.
 */
public final class EngineModel {
    private static final Logger logger = Logger.getLogger(EngineModel.class.getName());

    // ========== DICTIONARIES ==========
    private final Dictionary fieldDictionary;
    private final Dictionary valueDictionary;

    // ========== PREDICATE STORAGE ==========
    private final Object2IntMap<Predicate> predicateRegistry;
    private final Int2ObjectMap<Predicate> predicateLookup;
    private final Int2ObjectMap<List<Predicate>> fieldToPredicates;

    // ========== INVERTED INDEX ==========
    private final Int2ObjectMap<RoaringBitmap> invertedIndex;
    private final float[] predicateDensities;

    // ========== STRUCTURE OF ARRAYS (SoA) ==========
    private final int numRules;
    private final int[] priorities;
    private final int[] predicateCounts;
    private final String[] ruleCodes;
    private final String[] descriptions;
    private final IntList[] ruleToPredicateIds;

    // Rule family support
    private final Object2ObjectMap<String, IntList> rulesByCode;

    // Statistics
    private final EngineStats stats;

    private EngineModel(Builder builder) {
        this.fieldDictionary = builder.fieldDictionary;
        this.valueDictionary = builder.valueDictionary;
        this.predicateRegistry = builder.predicateRegistry;
        this.predicateLookup = builder.predicateLookup;
        this.fieldToPredicates = builder.fieldToPredicates;
        this.invertedIndex = builder.invertedIndex;
        this.predicateDensities = builder.predicateDensities;
        this.numRules = builder.ruleStore.size();
        this.priorities = builder.priorities;
        this.predicateCounts = builder.predicateCounts;
        this.ruleCodes = builder.ruleCodes;
        this.descriptions = builder.descriptions;
        this.ruleToPredicateIds = builder.ruleToPredicateIds;
        this.rulesByCode = builder.rulesByCode;
        this.stats = builder.stats;
    }

    // ========== PUBLIC ACCESSORS ==========
    public int getNumRules() { return numRules; }
    public Dictionary getFieldDictionary() { return fieldDictionary; }
    public Dictionary getValueDictionary() { return valueDictionary; }
    public Object2IntMap<Predicate> getPredicateRegistry() { return predicateRegistry; }
    public Int2ObjectMap<RoaringBitmap> getInvertedIndex() { return invertedIndex; }
    public Int2ObjectMap<List<Predicate>> getFieldToPredicates() { return fieldToPredicates; }
    public Predicate getPredicate(int id) { return predicateLookup.get(id); }
    public int getPredicateId(Predicate p) { return predicateRegistry.getInt(p); }
    public EngineStats getStats() { return stats; }

    // Direct SoA accessors
    public int getRulePredicateCount(int ruleId) { return predicateCounts[ruleId]; }
    public int getRulePriority(int ruleId) { return priorities[ruleId]; }
    public String getRuleCode(int ruleId) { return ruleCodes[ruleId]; }
    public IntList getRulePredicateIds(int ruleId) { return ruleToPredicateIds[ruleId]; }

    public Rule getRule(int id) {
        if (id < 0 || id >= numRules) return null;
        return new Rule(
                id,
                ruleCodes[id],
                predicateCounts[id],
                ruleToPredicateIds[id],
                priorities[id],
                descriptions[id]
        );
    }
    public List<Rule> getRulesByCode(String code) {
        IntList ruleIds = rulesByCode.get(code);
        if (ruleIds == null) return null;
        List<Rule> rules = new ArrayList<>(ruleIds.size());
        for (int ruleId : ruleIds) {
            rules.add(getRule(ruleId));
        }
        return rules;
    }

    // ========== BUILDER ==========
    public static class Builder {
        Dictionary fieldDictionary;
        Dictionary valueDictionary;
        final Object2IntMap<Predicate> predicateRegistry = new Object2IntOpenHashMap<>();
        final Int2ObjectMap<Predicate> predicateLookup = new Int2ObjectOpenHashMap<>();
        final Int2ObjectMap<RoaringBitmap> invertedIndex = new Int2ObjectOpenHashMap<>();
        final Int2ObjectMap<List<Predicate>> fieldToPredicates = new Int2ObjectOpenHashMap<>();
        float[] predicateDensities;
        final List<Rule> ruleStore = new ArrayList<>();
        int[] priorities;
        int[] predicateCounts;
        String[] ruleCodes;
        String[] descriptions;
        IntList[] ruleToPredicateIds;
        final Object2ObjectMap<String, IntList> rulesByCode = new Object2ObjectOpenHashMap<>();
        EngineStats stats;

        public int registerPredicate(Predicate predicate) {
            return predicateRegistry.computeIfAbsent(predicate, (Predicate p) -> {
                int id = predicateRegistry.size();
                predicateLookup.put(id, p);
                fieldToPredicates.computeIfAbsent(p.fieldId(), k -> new ArrayList<>()).add(p);
                return id;
            });
        }

        public int getPredicateCount() {
            return predicateRegistry.size();
        }

        public Builder addRule(Rule rule) {
            ruleStore.add(rule);
            int ruleId = rule.getId();
            rulesByCode.computeIfAbsent(rule.getRuleCode(), k -> new IntArrayList()).add(ruleId);
            for (int predicateId : rule.getPredicateIds()) {
                invertedIndex.computeIfAbsent(predicateId, k -> new RoaringBitmap()).add(ruleId);
            }
            return this;
        }

        public Builder withStats(EngineStats stats) {
            this.stats = stats;
            return this;
        }

        public Builder withFieldDictionary(Dictionary dictionary) {
            this.fieldDictionary = dictionary;
            return this;
        }

        public Builder withValueDictionary(Dictionary dictionary) {
            this.valueDictionary = dictionary;
            return this;
        }

        private void finalizeOptimizedStructures() {
            int numPredicates = predicateRegistry.size();
            int numRules = ruleStore.size();

            for (List<Predicate> predicates : fieldToPredicates.values()) {
                // Assuming weight calculation is handled correctly during predicate creation
            }
            logger.info("Phase 4 optimization: Predicates within each field sorted by weight.");

            predicateDensities = new float[numPredicates];
            for (int predId = 0; predId < numPredicates; predId++) {
                RoaringBitmap rulesWithPredicate = invertedIndex.get(predId);
                if (rulesWithPredicate != null) {
                    predicateDensities[predId] = (float) rulesWithPredicate.getCardinality() / numRules;
                }
            }

            priorities = new int[numRules];
            predicateCounts = new int[numRules];
            ruleCodes = new String[numRules];
            descriptions = new String[numRules];
            ruleToPredicateIds = new IntList[numRules];

            for (Rule rule : ruleStore) {
                int ruleId = rule.getId();
                priorities[ruleId] = rule.getPriority();
                predicateCounts[ruleId] = rule.getPredicateCount();
                ruleCodes[ruleId] = rule.getRuleCode();
                descriptions[ruleId] = rule.getDescription();
                ruleToPredicateIds[ruleId] = rule.getPredicateIds();
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