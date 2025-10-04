package os.toolset.ruleengine.core;

import it.unimi.dsi.fastutil.ints.*;
import org.roaringbitmap.RoaringBitmap;
import os.toolset.ruleengine.core.cache.BaseConditionCache;
import os.toolset.ruleengine.core.cache.FastCacheKeyGenerator;
import os.toolset.ruleengine.model.Event;
import os.toolset.ruleengine.model.Predicate;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * P0-A FIX: Pre-convert BitSet to RoaringBitmap once
 * P0-B FIX: Pre-compute cache key components at compile time (CORRECTED)
 */
public class BaseConditionEvaluator {
    private static final Logger logger = Logger.getLogger(BaseConditionEvaluator.class.getName());

    private final EngineModel model;
    private final BaseConditionCache cache;
    private final Map<Integer, BaseConditionSet> baseConditionSets;
    private final Int2ObjectMap<RoaringBitmap> setToRules;
    private long totalEvaluations = 0;
    private long cacheHits = 0;
    private long cacheMisses = 0;

    private static final ThreadLocal<List<BaseConditionSet>> APPLICABLE_SETS_BUFFER =
            ThreadLocal.withInitial(() -> new ArrayList<>(100));

    /**
     * P0-A FIX: Enhanced BaseConditionSet with pre-computed data
     */
    public static class BaseConditionSet {
        final int setId;
        final IntSet staticPredicateIds;
        final String signature;
        final RoaringBitmap affectedRules;
        final float avgSelectivity;

        // P0-B FIX: Pre-computed cache key components
        final int[] sortedPredicateIds;
        final long predicateSetHash;

        public BaseConditionSet(int setId, IntSet predicateIds, String signature,
                                RoaringBitmap rules, float selectivity) {
            this.setId = setId;
            this.staticPredicateIds = new IntOpenHashSet(predicateIds);
            this.signature = signature;
            this.affectedRules = rules;
            this.avgSelectivity = selectivity;

            this.sortedPredicateIds = predicateIds.toIntArray();
            Arrays.sort(this.sortedPredicateIds);
            this.predicateSetHash = computeHash(this.sortedPredicateIds);
        }

        private static long computeHash(int[] sorted) {
            long hash = 0xcbf29ce484222325L;
            for (int id : sorted) {
                hash ^= id;
                hash *= 0x100000001b3L;
            }
            return hash;
        }

        public int size() {
            return staticPredicateIds.size();
        }
    }

    /**
     * P0-A FIX: Enhanced EvaluationResult with pre-converted RoaringBitmap
     */
    public static class EvaluationResult {
        final BitSet matchingRules;
        final RoaringBitmap matchingRulesRoaring;
        final int predicatesEvaluated;
        final boolean fromCache;
        final long evaluationNanos;

        public EvaluationResult(BitSet matchingRules, int predicatesEvaluated,
                                boolean fromCache, long evaluationNanos) {
            this.matchingRules = matchingRules;
            this.predicatesEvaluated = predicatesEvaluated;
            this.fromCache = fromCache;
            this.evaluationNanos = evaluationNanos;

            // P0-A FIX: Convert BitSet to RoaringBitmap ONCE here
            this.matchingRulesRoaring = new RoaringBitmap();
            for (int i = matchingRules.nextSetBit(0); i >= 0;
                 i = matchingRules.nextSetBit(i + 1)) {
                this.matchingRulesRoaring.add(i);
            }
        }
    }

    public BaseConditionEvaluator(EngineModel model, BaseConditionCache cache) {
        this.model = model;
        this.cache = cache;
        this.baseConditionSets = new HashMap<>();
        this.setToRules = new Int2ObjectOpenHashMap<>();
        extractBaseConditionSets();

        logger.info(String.format(
                "BaseConditionEvaluator initialized: %d base sets extracted from %d combinations (%.1f%% reduction)",
                baseConditionSets.size(),
                model.getNumRules(),
                (1.0 - (double) baseConditionSets.size() / model.getNumRules()) * 100
        ));
    }

    private void extractBaseConditionSets() {
        Map<String, List<Integer>> signatureToCombinations = new HashMap<>();

        for (int combinationId = 0; combinationId < model.getNumRules(); combinationId++) {
            IntList predicateIds = model.getCombinationPredicateIds(combinationId);
            IntList staticPredicates = new IntArrayList();

            for (int predId : predicateIds) {
                Predicate pred = model.getPredicate(predId);
                if (isStaticPredicate(pred)) {
                    staticPredicates.add(predId);
                }
            }

            if (!staticPredicates.isEmpty()) {
                String signature = computePredicateSignature(staticPredicates);
                signatureToCombinations.computeIfAbsent(signature, k -> new ArrayList<>())
                        .add(combinationId);
            }
        }

        int setId = 0;
        for (Map.Entry<String, List<Integer>> entry : signatureToCombinations.entrySet()) {
            String signature = entry.getKey();
            List<Integer> combinations = entry.getValue();

            if (combinations.size() >= 1) {
                RoaringBitmap combinationBitmap = new RoaringBitmap();
                combinations.forEach(combinationBitmap::add);

                IntSet staticPreds = new IntOpenHashSet();
                IntList firstCombinationPredicates = model.getCombinationPredicateIds(
                        combinations.get(0));

                for (int predId : firstCombinationPredicates) {
                    if (isStaticPredicate(model.getPredicate(predId))) {
                        staticPreds.add(predId);
                    }
                }

                float avgSelectivity = calculateAverageSelectivity(staticPreds);

                BaseConditionSet baseSet = new BaseConditionSet(
                        setId, staticPreds, signature, combinationBitmap, avgSelectivity);

                baseConditionSets.put(setId, baseSet);
                setToRules.put(setId, combinationBitmap);
                setId++;
            }
        }

        if (logger.isLoggable(Level.FINE)) {
            List<BaseConditionSet> sortedSets = new ArrayList<>(baseConditionSets.values());
            sortedSets.sort(Comparator.comparingDouble(s -> s.avgSelectivity));

            for (BaseConditionSet set : sortedSets) {
                logger.fine(String.format(
                        "Base set %d: %d predicates, %d combinations, %.2f selectivity",
                        set.setId, set.size(), set.affectedRules.getCardinality(),
                        set.avgSelectivity
                ));
            }
        }
    }

    public CompletableFuture<EvaluationResult> evaluateBaseConditions(Event event) {
        long startTime = System.nanoTime();
        totalEvaluations++;

        List<BaseConditionSet> applicableSets = APPLICABLE_SETS_BUFFER.get();
        applicableSets.clear();

        for (BaseConditionSet set : baseConditionSets.values()) {
            if (shouldEvaluateSet(set, event)) {
                applicableSets.add(set);
            }
        }

        if (applicableSets.size() > 1) {
            applicableSets.sort((a, b) -> Float.compare(a.avgSelectivity, b.avgSelectivity));
        }

        if (applicableSets.isEmpty()) {
            BitSet allRules = new BitSet(model.getNumRules());
            allRules.set(0, model.getNumRules());
            return CompletableFuture.completedFuture(
                    new EvaluationResult(allRules, 0, false, System.nanoTime() - startTime));
        }

        String cacheKey = generateCacheKeyOptimized(event, applicableSets);

        return cache.get(cacheKey).thenCompose(cached -> {
            if (cached.isPresent()) {
                cacheHits++;
                BitSet result = cached.get().result();
                long duration = System.nanoTime() - startTime;

                if (logger.isLoggable(Level.FINE)) {
                    logger.fine(String.format("Cache hit for event %s: %d combinations match (%.2f ms)",
                            event.getEventId(), result.cardinality(), duration / 1_000_000.0));
                }

                return CompletableFuture.completedFuture(
                        new EvaluationResult(result, 0, true, duration));
            } else {
                cacheMisses++;
                return evaluateAndCache(event, applicableSets, cacheKey, startTime);
            }
        });
    }

    private CompletableFuture<EvaluationResult> evaluateAndCache(Event event,
                                                                 List<BaseConditionSet> sets,
                                                                 String cacheKey,
                                                                 long startTime) {
        BitSet matchingCombinations = new BitSet(model.getNumRules());
        matchingCombinations.set(0, model.getNumRules());

        Int2ObjectMap<Object> eventAttrs = event.getEncodedAttributes(
                model.getFieldDictionary(), model.getValueDictionary());

        int totalPredicatesEvaluated = 0;

        for (BaseConditionSet set : sets) {
            boolean allMatch = true;
            for (int predId : set.staticPredicateIds) {
                Predicate pred = model.getPredicate(predId);
                Object eventValue = eventAttrs.get(pred.fieldId());
                totalPredicatesEvaluated++;

                if (!pred.evaluate(eventValue)) {
                    allMatch = false;
                    break;
                }
            }

            if (!allMatch) {
                set.affectedRules.forEach(
                        (int combinationId) -> matchingCombinations.clear(combinationId));
            }
        }

        final int predicatesEval = totalPredicatesEvaluated;

        return cache.put(cacheKey, matchingCombinations, 5, TimeUnit.MINUTES)
                .thenApply(v -> {
                    long duration = System.nanoTime() - startTime;

                    if (logger.isLoggable(Level.FINE)) {
                        logger.fine(String.format(
                                "Evaluated %d base predicates for event %s: %d combinations match (%.2f ms)",
                                predicatesEval, event.getEventId(),
                                matchingCombinations.cardinality(), duration / 1_000_000.0));
                    }

                    return new EvaluationResult(
                            matchingCombinations, predicatesEval, false, duration);
                });
    }

    /**
     * P0-B FIX (CORRECTED): Optimized cache key generation
     *
     * CRITICAL FIX: Always include event attribute values in cache key!
     * The previous version had a bug where multi-set keys didn't include values.
     */
    private String generateCacheKeyOptimized(Event event, List<BaseConditionSet> sets) {
        Int2ObjectMap<Object> eventAttrs = event.getEncodedAttributes(
                model.getFieldDictionary(), model.getValueDictionary());

        if (sets.size() == 1) {
            // P0-B: Single set - use pre-computed sorted array directly
            BaseConditionSet set = sets.get(0);
            return FastCacheKeyGenerator.generateKey(
                    eventAttrs,
                    set.sortedPredicateIds,
                    set.sortedPredicateIds.length
            );
        } else {
            // P0-B FIX (CORRECTED): Multi-set - merge predicate IDs and generate proper key
            // This ensures event attribute values are included in the cache key!
            IntSet allPredicates = new IntOpenHashSet();
            for (BaseConditionSet set : sets) {
                // Use pre-sorted arrays to minimize work
                for (int predId : set.sortedPredicateIds) {
                    allPredicates.add(predId);
                }
            }

            int[] merged = allPredicates.toIntArray();
            Arrays.sort(merged);

            return FastCacheKeyGenerator.generateKey(eventAttrs, merged, merged.length);
        }
    }

    boolean isStaticPredicate(Predicate pred) {
        if (pred == null) return false;

        Set<String> dynamicFields = Set.of(
                "TIMESTAMP", "RANDOM", "SESSION_ID", "REQUEST_ID", "CORRELATION_ID");

        String fieldName = model.getFieldDictionary().decode(pred.fieldId());
        if (dynamicFields.contains(fieldName)) {
            return false;
        }

        return pred.operator() == Predicate.Operator.EQUAL_TO ||
                pred.operator() == Predicate.Operator.IS_ANY_OF;
    }

    private boolean shouldEvaluateSet(BaseConditionSet set, Event event) {
        return true;
    }

    private String computePredicateSignature(IntList predicateIds) {
        int[] sorted = predicateIds.toIntArray();
        Arrays.sort(sorted);

        StringBuilder sb = new StringBuilder();
        for (int id : sorted) {
            Predicate pred = model.getPredicate(id);
            String fieldName = model.getFieldDictionary().decode(pred.fieldId());
            sb.append(fieldName).append("::").append(pred.operator())
                    .append("::").append(pred.value()).append(";");
        }

        return sb.toString();
    }

    private float calculateAverageSelectivity(IntSet predicateIds) {
        if (predicateIds.isEmpty()) return 0.5f;

        double sum = 0;
        for (int predId : predicateIds) {
            sum += model.getPredicate(predId).selectivity();
        }

        return (float) (sum / predicateIds.size());
    }

    public Map<String, Object> getMetrics() {
        BaseConditionCache.CacheMetrics cacheMetrics = cache.getMetrics();
        Map<String, Object> metrics = new LinkedHashMap<>();

        metrics.put("totalEvaluations", totalEvaluations);
        metrics.put("cacheHits", cacheHits);
        metrics.put("cacheMisses", cacheMisses);
        metrics.put("cacheHitRate",
                totalEvaluations > 0 ? (double) cacheHits / totalEvaluations : 0.0);
        metrics.put("baseConditionSets", baseConditionSets.size());

        double avgSetSize = 0.0;
        if (!baseConditionSets.isEmpty()) {
            int totalSize = 0;
            for (BaseConditionSet set : baseConditionSets.values()) {
                totalSize += set.size();
            }
            avgSetSize = (double) totalSize / baseConditionSets.size();
        }
        metrics.put("avgSetSize", avgSetSize);

        metrics.put("cache", Map.of(
                "size", cacheMetrics.size(),
                "hitRate", cacheMetrics.getHitRate(),
                "evictions", cacheMetrics.evictions(),
                "avgGetTimeNanos", cacheMetrics.avgGetTimeNanos(),
                "avgPutTimeNanos", cacheMetrics.avgPutTimeNanos()
        ));

        return metrics;
    }
}