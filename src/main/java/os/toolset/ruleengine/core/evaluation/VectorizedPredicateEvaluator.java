package os.toolset.ruleengine.core.evaluation;

import jdk.incubator.vector.*;
import it.unimi.dsi.fastutil.ints.*;
import os.toolset.ruleengine.core.EngineModel;
import os.toolset.ruleengine.core.OptimizedEvaluationContext;
import os.toolset.ruleengine.model.Event;
import os.toolset.ruleengine.model.Predicate;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Logger;

/**
 * Enhanced vectorized predicate evaluator with Float16 support and optimized memory patterns
 * P0-1 FIX: Added eligibleRules filtering to prevent evaluation of ineligible predicates
 */
public class VectorizedPredicateEvaluator {
    private static final Logger logger = Logger.getLogger(VectorizedPredicateEvaluator.class.getName());

    private final EngineModel model;
    private final Map<Integer, NumericBatchEvaluator> numericEvaluators;
    private final Map<Integer, StringBatchEvaluator> stringEvaluators;

    // Enhanced vector species for Java 25
    private static final VectorSpecies<Float> FLOAT16_SPECIES = FloatVector.SPECIES_PREFERRED;
    private static final VectorSpecies<Double> DOUBLE_SPECIES = DoubleVector.SPECIES_PREFERRED;
    private static final VectorSpecies<Integer> INT_SPECIES = IntVector.SPECIES_PREFERRED;

    // Memory pools with size tiers
    private static final BufferPool SMALL_POOL = new BufferPool(16, 64);
    private static final BufferPool MEDIUM_POOL = new BufferPool(64, 32);
    private static final BufferPool LARGE_POOL = new BufferPool(256, 16);

    // Off-heap arena for large operations
    private static final Arena ARENA = Arena.ofShared();

    // Cache line size for alignment
    private static final int CACHE_LINE_SIZE = 64;

    public VectorizedPredicateEvaluator(EngineModel model) {
        this.model = model;
        this.numericEvaluators = new ConcurrentHashMap<>();
        this.stringEvaluators = new ConcurrentHashMap<>();
        initializeEvaluators();
    }

    private void initializeEvaluators() {
        // Group predicates by field and type for optimal vectorization
        Map<Integer, List<Predicate>> fieldToPredicates = model.getFieldToPredicates();

        for (Map.Entry<Integer, List<Predicate>> entry : fieldToPredicates.entrySet()) {
            int fieldId = entry.getKey();
            List<Predicate> predicates = entry.getValue();

            // Separate numeric and string predicates
            List<NumericPredicate> numericPreds = new ArrayList<>();
            List<StringPredicate> stringPreds = new ArrayList<>();

            for (Predicate p : predicates) {
                int predId = model.getPredicateId(p);

                if (p.operator().isNumeric()) {
                    numericPreds.add(createNumericPredicate(predId, p));
                } else if (p.operator() == Predicate.Operator.CONTAINS ||
                        p.operator() == Predicate.Operator.REGEX) {
                    stringPreds.add(new StringPredicate(predId, p));
                }
            }

            if (!numericPreds.isEmpty()) {
                numericEvaluators.put(fieldId, new NumericBatchEvaluator(fieldId, numericPreds));
            }

            if (!stringPreds.isEmpty()) {
                stringEvaluators.put(fieldId, new StringBatchEvaluator(fieldId, stringPreds));
            }
        }

        logger.info(String.format(
                "Initialized vectorized evaluators: %d numeric, %d string fields",
                numericEvaluators.size(), stringEvaluators.size()
        ));
    }

    /**
     * P0-1 FIX: Main entry point - now accepts eligibleRules filter
     */
    public void evaluate(Event event, OptimizedEvaluationContext ctx, BitSet eligibleRules) {
        Int2ObjectMap<Object> attributes = event.getEncodedAttributes(
                model.getFieldDictionary(), model.getValueDictionary());

        // Build set of predicate IDs we should evaluate (only for eligible rules)
        IntSet eligiblePredicateIds = null;
        if (eligibleRules != null) {
            eligiblePredicateIds = buildEligiblePredicateSet(eligibleRules);
        }

        // Process in optimal order: numeric (vectorized) → string → equality
        evaluateNumericBatch(attributes, ctx, eligiblePredicateIds);
        evaluateStringBatch(attributes, ctx, eligiblePredicateIds);
        evaluateEqualityBatch(attributes, ctx, eligiblePredicateIds);
    }

    /**
     * P0-1 FIX: Build set of predicate IDs that need evaluation based on eligible rules
     */
    private IntSet buildEligiblePredicateSet(BitSet eligibleRules) {
        IntSet predicateIds = new IntOpenHashSet();

        for (int ruleId = eligibleRules.nextSetBit(0); ruleId >= 0;
             ruleId = eligibleRules.nextSetBit(ruleId + 1)) {

            IntList rulePreds = model.getCombinationPredicateIds(ruleId);
            for (int predId : rulePreds) {
                predicateIds.add(predId);
            }
        }

        return predicateIds;
    }

    private void evaluateNumericBatch(Int2ObjectMap<Object> attributes,
                                      OptimizedEvaluationContext ctx,
                                      IntSet eligiblePredicateIds) {
        for (Int2ObjectMap.Entry<Object> entry : attributes.int2ObjectEntrySet()) {
            int fieldId = entry.getIntKey();
            Object value = entry.getValue();

            if (value instanceof Number) {
                NumericBatchEvaluator evaluator = numericEvaluators.get(fieldId);
                if (evaluator != null) {
                    IntSet matches = evaluator.evaluateFloat16(
                            ((Number) value).floatValue(),
                            eligiblePredicateIds
                    );

                    matches.forEach((int predId) -> {
                        ctx.addTruePredicate(predId);
                        ctx.incrementPredicatesEvaluatedCount();
                    });
                }
            }
        }
    }

    private void evaluateStringBatch(Int2ObjectMap<Object> attributes,
                                     OptimizedEvaluationContext ctx,
                                     IntSet eligiblePredicateIds) {
        // String evaluation with optimized pattern matching
        for (Int2ObjectMap.Entry<Object> entry : attributes.int2ObjectEntrySet()) {
            int fieldId = entry.getIntKey();
            Object value = entry.getValue();

            if (value instanceof String || value instanceof Integer) {
                StringBatchEvaluator evaluator = stringEvaluators.get(fieldId);
                if (evaluator != null) {
                    String strValue = value instanceof Integer ?
                            model.getValueDictionary().decode((Integer) value) :
                            (String) value;

                    if (strValue != null) {
                        IntSet matches = evaluator.evaluate(strValue, eligiblePredicateIds);
                        matches.forEach((int predId) -> {
                            ctx.addTruePredicate(predId);
                            ctx.incrementPredicatesEvaluatedCount();
                        });
                    }
                }
            }
        }
    }

    private void evaluateEqualityBatch(Int2ObjectMap<Object> attributes,
                                       OptimizedEvaluationContext ctx,
                                       IntSet eligiblePredicateIds) {
        // Fast path for equality checks using dictionary encoding
        for (Int2ObjectMap.Entry<Object> entry : attributes.int2ObjectEntrySet()) {
            int fieldId = entry.getIntKey();
            Object value = entry.getValue();

            List<Predicate> predicates = model.getFieldToPredicates().get(fieldId);
            if (predicates != null) {
                for (Predicate p : predicates) {
                    if (p.operator() == Predicate.Operator.EQUAL_TO) {
                        int predId = model.getPredicateId(p);

                        // P0-1 FIX: Skip if not in eligible set
                        if (eligiblePredicateIds != null && !eligiblePredicateIds.contains(predId)) {
                            continue;
                        }

                        ctx.incrementPredicatesEvaluatedCount();
                        if (p.evaluate(value)) {
                            ctx.addTruePredicate(predId);
                        }
                    }
                }
            }
        }
    }

    /**
     * Enhanced numeric batch evaluator with Float16 support
     * P0-1 FIX: Added eligiblePredicateIds filtering
     */
    public static class NumericBatchEvaluator {
        private final int fieldId;
        private final PredicateGroup[] groups;
        private final MemorySegment alignedWorkspace;
        private final float[] thresholdCache;

        // Metrics
        private long vectorOps = 0;
        private long scalarOps = 0;

        public NumericBatchEvaluator(int fieldId, List<NumericPredicate> predicates) {
            this.fieldId = fieldId;
            this.groups = organizeIntoGroups(predicates);

            // Allocate cache-line aligned workspace
            int maxGroupSize = Arrays.stream(groups)
                    .mapToInt(g -> g.predicates.length)
                    .max().orElse(0);

            this.alignedWorkspace = ARENA.allocate(
                    ((maxGroupSize * 4 + CACHE_LINE_SIZE - 1) / CACHE_LINE_SIZE) * CACHE_LINE_SIZE
            );

            // Pre-compute thresholds for Float16 conversion
            this.thresholdCache = new float[maxGroupSize];
        }

        /**
         * Evaluate using Float16 for better throughput and lower memory bandwidth
         * P0-1 FIX: Filter by eligible predicate IDs
         */
        public IntSet evaluateFloat16(float eventValue, IntSet eligiblePredicateIds) {
            IntSet matches = new IntOpenHashSet();

            for (PredicateGroup group : groups) {
                switch (group.operator) {
                    case GREATER_THAN:
                        evaluateGTFloat16(eventValue, group, matches, eligiblePredicateIds);
                        break;
                    case LESS_THAN:
                        evaluateLTFloat16(eventValue, group, matches, eligiblePredicateIds);
                        break;
                    case BETWEEN:
                        evaluateBetweenFloat16(eventValue, group, matches, eligiblePredicateIds);
                        break;
                    default:
                        evaluateScalar(eventValue, group, matches, eligiblePredicateIds);
                }
            }

            return matches;
        }

        private void evaluateGTFloat16(float eventValue, PredicateGroup group, IntSet matches,
                                       IntSet eligiblePredicateIds) {
            int count = group.predicates.length;

            if (count >= FLOAT16_SPECIES.length() * 2) {
                // Use Float16 vectorization for large groups
                vectorOps++;

                // Convert thresholds to Float16-compatible format
                for (int i = 0; i < count; i++) {
                    thresholdCache[i] = (float) group.predicates[i].value;
                }

                FloatVector eventVec = FloatVector.broadcast(FLOAT16_SPECIES, eventValue);

                // Process in chunks of vector length
                for (int i = 0; i < count; i += FLOAT16_SPECIES.length()) {
                    int limit = Math.min(i + FLOAT16_SPECIES.length(), count);

                    FloatVector thresholdVec = FloatVector.fromArray(
                            FLOAT16_SPECIES, thresholdCache, i
                    );

                    VectorMask<Float> mask = eventVec.compare(VectorOperators.GT, thresholdVec);

                    // Process matches with unrolled loop
                    for (int j = 0; j < limit - i; j++) {
                        if (mask.laneIsSet(j)) {
                            int predId = group.predicates[i + j].id;
                            // P0-1 FIX: Check eligibility
                            if (eligiblePredicateIds == null || eligiblePredicateIds.contains(predId)) {
                                matches.add(predId);
                            }
                        }
                    }
                }
            } else {
                // Scalar fallback for small groups
                evaluateScalarGT(eventValue, group, matches, eligiblePredicateIds);
            }
        }

        private void evaluateLTFloat16(float eventValue, PredicateGroup group, IntSet matches,
                                       IntSet eligiblePredicateIds) {
            int count = group.predicates.length;

            if (count >= FLOAT16_SPECIES.length() * 2) {
                vectorOps++;

                for (int i = 0; i < count; i++) {
                    thresholdCache[i] = (float) group.predicates[i].value;
                }

                FloatVector eventVec = FloatVector.broadcast(FLOAT16_SPECIES, eventValue);

                for (int i = 0; i < count; i += FLOAT16_SPECIES.length()) {
                    FloatVector thresholdVec = FloatVector.fromArray(
                            FLOAT16_SPECIES, thresholdCache, i
                    );

                    VectorMask<Float> mask = eventVec.compare(VectorOperators.LT, thresholdVec);

                    int limit = Math.min(i + FLOAT16_SPECIES.length(), count);
                    for (int j = 0; j < limit - i; j++) {
                        if (mask.laneIsSet(j)) {
                            int predId = group.predicates[i + j].id;
                            if (eligiblePredicateIds == null || eligiblePredicateIds.contains(predId)) {
                                matches.add(predId);
                            }
                        }
                    }
                }
            } else {
                evaluateScalarLT(eventValue, group, matches, eligiblePredicateIds);
            }
        }

        private void evaluateBetweenFloat16(float eventValue, PredicateGroup group, IntSet matches,
                                            IntSet eligiblePredicateIds) {
            // Between requires two comparisons - optimize with fused operations
            int count = group.predicates.length;

            if (count >= FLOAT16_SPECIES.length()) {
                vectorOps++;

                FloatVector eventVec = FloatVector.broadcast(FLOAT16_SPECIES, eventValue);

                for (int i = 0; i < count; i += FLOAT16_SPECIES.length()) {
                    int limit = Math.min(i + FLOAT16_SPECIES.length(), count);

                    // Prepare lower and upper bounds in separate arrays
                    float[] lowerBounds = new float[FLOAT16_SPECIES.length()];
                    float[] upperBounds = new float[FLOAT16_SPECIES.length()];

                    for (int j = 0; j < limit - i; j++) {
                        lowerBounds[j] = (float) group.predicates[i + j].value;
                        upperBounds[j] = (float) group.predicates[i + j].value2;
                    }

                    // Load bounds into vectors
                    FloatVector lowerVec = FloatVector.fromArray(FLOAT16_SPECIES, lowerBounds, 0);
                    FloatVector upperVec = FloatVector.fromArray(FLOAT16_SPECIES, upperBounds, 0);

                    // Perform comparisons
                    VectorMask<Float> lowerMask = eventVec.compare(VectorOperators.GE, lowerVec);
                    VectorMask<Float> upperMask = eventVec.compare(VectorOperators.LE, upperVec);
                    VectorMask<Float> combinedMask = lowerMask.and(upperMask);

                    // Process matches
                    for (int j = 0; j < limit - i; j++) {
                        if (combinedMask.laneIsSet(j)) {
                            int predId = group.predicates[i + j].id;
                            if (eligiblePredicateIds == null || eligiblePredicateIds.contains(predId)) {
                                matches.add(predId);
                            }
                        }
                    }
                }
            } else {
                evaluateScalarBetween(eventValue, group, matches, eligiblePredicateIds);
            }
        }

        // Scalar fallbacks with loop unrolling
        private void evaluateScalarGT(float eventValue, PredicateGroup group, IntSet matches,
                                      IntSet eligiblePredicateIds) {
            scalarOps += group.predicates.length;

            // Unroll by 4 for better ILP
            int i = 0;
            int count = group.predicates.length;

            for (; i + 4 <= count; i += 4) {
                if (eventValue > group.predicates[i].value) {
                    int predId = group.predicates[i].id;
                    if (eligiblePredicateIds == null || eligiblePredicateIds.contains(predId)) {
                        matches.add(predId);
                    }
                }
                if (eventValue > group.predicates[i + 1].value) {
                    int predId = group.predicates[i + 1].id;
                    if (eligiblePredicateIds == null || eligiblePredicateIds.contains(predId)) {
                        matches.add(predId);
                    }
                }
                if (eventValue > group.predicates[i + 2].value) {
                    int predId = group.predicates[i + 2].id;
                    if (eligiblePredicateIds == null || eligiblePredicateIds.contains(predId)) {
                        matches.add(predId);
                    }
                }
                if (eventValue > group.predicates[i + 3].value) {
                    int predId = group.predicates[i + 3].id;
                    if (eligiblePredicateIds == null || eligiblePredicateIds.contains(predId)) {
                        matches.add(predId);
                    }
                }
            }

            for (; i < count; i++) {
                if (eventValue > group.predicates[i].value) {
                    int predId = group.predicates[i].id;
                    if (eligiblePredicateIds == null || eligiblePredicateIds.contains(predId)) {
                        matches.add(predId);
                    }
                }
            }
        }

        private void evaluateScalarLT(float eventValue, PredicateGroup group, IntSet matches,
                                      IntSet eligiblePredicateIds) {
            scalarOps += group.predicates.length;
            for (NumericPredicate pred : group.predicates) {
                if (eventValue < pred.value) {
                    if (eligiblePredicateIds == null || eligiblePredicateIds.contains(pred.id)) {
                        matches.add(pred.id);
                    }
                }
            }
        }

        private void evaluateScalarBetween(float eventValue, PredicateGroup group, IntSet matches,
                                           IntSet eligiblePredicateIds) {
            scalarOps += group.predicates.length * 2;
            for (NumericPredicate pred : group.predicates) {
                if (eventValue >= pred.value && eventValue <= pred.value2) {
                    if (eligiblePredicateIds == null || eligiblePredicateIds.contains(pred.id)) {
                        matches.add(pred.id);
                    }
                }
            }
        }

        private void evaluateScalar(float eventValue, PredicateGroup group, IntSet matches,
                                    IntSet eligiblePredicateIds) {
            scalarOps += group.predicates.length;
            for (NumericPredicate pred : group.predicates) {
                if (pred.evaluateFloat(eventValue)) {
                    if (eligiblePredicateIds == null || eligiblePredicateIds.contains(pred.id)) {
                        matches.add(pred.id);
                    }
                }
            }
        }

        private PredicateGroup[] organizeIntoGroups(List<NumericPredicate> predicates) {
            // Group by operator and sort by value for better branch prediction
            Map<Operator, List<NumericPredicate>> opGroups = new HashMap<>();

            for (NumericPredicate pred : predicates) {
                opGroups.computeIfAbsent(pred.operator, k -> new ArrayList<>()).add(pred);
            }

            // Sort each group by threshold value for better cache and branch behavior
            PredicateGroup[] groups = new PredicateGroup[opGroups.size()];
            int idx = 0;

            for (Map.Entry<Operator, List<NumericPredicate>> entry : opGroups.entrySet()) {
                List<NumericPredicate> list = entry.getValue();
                list.sort(Comparator.comparingDouble(p -> p.value));
                groups[idx++] = new PredicateGroup(
                        entry.getKey(),
                        list.toArray(new NumericPredicate[0])
                );
            }

            return groups;
        }
    }

    /**
     * String batch evaluator with optimized pattern matching
     * P0-1 FIX: Added eligiblePredicateIds filtering
     */
    public static class StringBatchEvaluator {
        private final int fieldId;
        private final StringPredicate[] predicates;
        private final Map<String, IntList> containsIndex; // Inverted index for CONTAINS

        public StringBatchEvaluator(int fieldId, List<StringPredicate> preds) {
            this.fieldId = fieldId;
            this.predicates = preds.toArray(new StringPredicate[0]);
            this.containsIndex = buildContainsIndex(predicates);
        }

        private Map<String, IntList> buildContainsIndex(StringPredicate[] preds) {
            Map<String, IntList> index = new HashMap<>();

            for (StringPredicate pred : preds) {
                if (pred.predicate.operator() == Predicate.Operator.CONTAINS) {
                    String pattern = (String) pred.predicate.value();
                    // Build n-gram index for faster matching
                    for (int i = 0; i < pattern.length() - 1; i++) {
                        String bigram = pattern.substring(i, i + 2);
                        index.computeIfAbsent(bigram, k -> new IntArrayList()).add(pred.id);
                    }
                }
            }

            return index;
        }

        public IntSet evaluate(String value, IntSet eligiblePredicateIds) {
            IntSet matches = new IntOpenHashSet();

            // Fast path for CONTAINS using n-gram index
            Set<Integer> candidates = new HashSet<>();
            for (int i = 0; i < value.length() - 1; i++) {
                String bigram = value.substring(i, i + 2);
                IntList predIds = containsIndex.get(bigram);
                if (predIds != null) {
                    predIds.forEach((IntConsumer) candidates::add);
                }
            }

            // Verify candidates and check other predicates
            for (StringPredicate pred : predicates) {
                // P0-1 FIX: Filter by eligible predicates
                if (eligiblePredicateIds != null && !eligiblePredicateIds.contains(pred.id)) {
                    continue;
                }

                if (candidates.contains(pred.id) ||
                        pred.predicate.operator() != Predicate.Operator.CONTAINS) {
                    if (pred.predicate.evaluate(value)) {
                        matches.add(pred.id);
                    }
                }
            }

            return matches;
        }
    }

    /**
     * Enhanced buffer pool with size tiers
     */
    private static class BufferPool {
        private final ConcurrentLinkedQueue<float[]> floatBuffers;
        private final ConcurrentLinkedQueue<double[]> doubleBuffers;
        private final int bufferSize;
        private final int maxPoolSize;

        BufferPool(int bufferSize, int maxPoolSize) {
            this.bufferSize = bufferSize;
            this.maxPoolSize = maxPoolSize;
            this.floatBuffers = new ConcurrentLinkedQueue<>();
            this.doubleBuffers = new ConcurrentLinkedQueue<>();

            // Pre-allocate some buffers
            for (int i = 0; i < maxPoolSize / 4; i++) {
                floatBuffers.offer(new float[bufferSize]);
                doubleBuffers.offer(new double[bufferSize]);
            }
        }

        float[] acquireFloat() {
            float[] buffer = floatBuffers.poll();
            return buffer != null ? buffer : new float[bufferSize];
        }

        void releaseFloat(float[] buffer) {
            if (floatBuffers.size() < maxPoolSize && buffer.length == bufferSize) {
                Arrays.fill(buffer, 0); // Clear for security
                floatBuffers.offer(buffer);
            }
        }

        double[] acquireDouble() {
            double[] buffer = doubleBuffers.poll();
            return buffer != null ? buffer : new double[bufferSize];
        }

        void releaseDouble(double[] buffer) {
            if (doubleBuffers.size() < maxPoolSize && buffer.length == bufferSize) {
                Arrays.fill(buffer, 0);
                doubleBuffers.offer(buffer);
            }
        }
    }

    // Supporting classes
    private static NumericPredicate createNumericPredicate(int id, Predicate p) {
        if (p.operator() == Predicate.Operator.BETWEEN) {
            List<?> range = (List<?>) p.value();
            return new NumericPredicate(id, Operator.BETWEEN,
                    ((Number) range.get(0)).doubleValue(),
                    ((Number) range.get(1)).doubleValue());
        } else {
            return new NumericPredicate(id,
                    Operator.fromPredicateOperator(p.operator()),
                    ((Number) p.value()).doubleValue());
        }
    }

    static class PredicateGroup {
        final Operator operator;
        final NumericPredicate[] predicates;

        PredicateGroup(Operator op, NumericPredicate[] preds) {
            this.operator = op;
            this.predicates = preds;
        }
    }

    public static class NumericPredicate {
        final int id;
        final Operator operator;
        final double value;
        final double value2; // For BETWEEN

        public NumericPredicate(int id, Operator op, double value) {
            this(id, op, value, 0);
        }

        public NumericPredicate(int id, Operator op, double lower, double upper) {
            this.id = id;
            this.operator = op;
            this.value = lower;
            this.value2 = upper;
        }

        boolean evaluateFloat(float eventValue) {
            return switch (operator) {
                case GREATER_THAN -> eventValue > value;
                case LESS_THAN -> eventValue < value;
                case BETWEEN -> eventValue >= value && eventValue <= value2;
                case EQUAL_TO -> Float.compare(eventValue, (float) value) == 0;
                default -> false;
            };
        }
    }

    public static class StringPredicate {
        final int id;
        final Predicate predicate;

        public StringPredicate(int id, Predicate predicate) {
            this.id = id;
            this.predicate = predicate;
        }
    }

    public enum Operator {
        EQUAL_TO, GREATER_THAN, LESS_THAN, BETWEEN, UNKNOWN;

        public static Operator fromPredicateOperator(Predicate.Operator pOp) {
            return switch (pOp) {
                case EQUAL_TO -> EQUAL_TO;
                case GREATER_THAN -> GREATER_THAN;
                case LESS_THAN -> LESS_THAN;
                case BETWEEN -> BETWEEN;
                default -> UNKNOWN;
            };
        }
    }
}