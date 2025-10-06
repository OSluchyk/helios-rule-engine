package os.toolset.ruleengine.core.evaluation;

import jdk.incubator.vector.*;
import it.unimi.dsi.fastutil.ints.*;
import os.toolset.ruleengine.core.EngineModel;
import os.toolset.ruleengine.core.OptimizedEvaluationContext;
import os.toolset.ruleengine.model.Predicate;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Logger;

public class VectorizedPredicateEvaluator {
    private static final Logger logger = Logger.getLogger(VectorizedPredicateEvaluator.class.getName());

    private final EngineModel model;
    private final Map<Integer, NumericBatchEvaluator> numericEvaluators;
    private final Map<Integer, StringBatchEvaluator> stringEvaluators;

    private static final VectorSpecies<Float> FLOAT_SPECIES = FloatVector.SPECIES_PREFERRED;

    private static final Arena ARENA = Arena.ofShared();
    private static final int CACHE_LINE_SIZE = 64;

    public VectorizedPredicateEvaluator(EngineModel model) {
        this.model = model;
        this.numericEvaluators = new ConcurrentHashMap<>();
        this.stringEvaluators = new ConcurrentHashMap<>();
        initializeEvaluators();
    }

    private void initializeEvaluators() {
        Map<Integer, List<Predicate>> fieldToPredicates = model.getFieldToPredicates();

        for (Map.Entry<Integer, List<Predicate>> entry : fieldToPredicates.entrySet()) {
            int fieldId = entry.getKey();
            List<Predicate> predicates = entry.getValue();

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

    public void evaluateField(int fieldId, Int2ObjectMap<Object> attributes,
                              OptimizedEvaluationContext ctx, IntSet eligiblePredicateIds) {
        Object value = attributes.get(fieldId);
        if (value == null) {
            return;
        }

        if (value instanceof Number) {
            NumericBatchEvaluator evaluator = numericEvaluators.get(fieldId);
            if (evaluator != null) {
                IntSet matches = evaluator.evaluateFloat16(((Number) value).floatValue(), eligiblePredicateIds);
                matches.forEach((int predId) -> {
                    ctx.addTruePredicate(predId);
                    ctx.incrementPredicatesEvaluatedCount();
                });
            }
        }
        else if (value instanceof String || value instanceof Integer) {
            String strValue = value instanceof Integer ?
                    model.getValueDictionary().decode((Integer) value) :
                    (String) value;

            if (strValue != null) {
                StringBatchEvaluator evaluator = stringEvaluators.get(fieldId);
                if (evaluator != null) {
                    IntSet matches = evaluator.evaluate(strValue, eligiblePredicateIds);
                    matches.forEach((int predId) -> {
                        ctx.addTruePredicate(predId);
                        ctx.incrementPredicatesEvaluatedCount();
                    });
                }
            }
        }

        evaluateEqualityForField(fieldId, value, ctx, eligiblePredicateIds);
    }


    private void evaluateEqualityForField(int fieldId, Object value,
                                          OptimizedEvaluationContext ctx,
                                          IntSet eligiblePredicateIds) {
        List<Predicate> predicates = model.getFieldToPredicates().get(fieldId);
        if (predicates != null) {
            for (Predicate p : predicates) {
                if (p.operator() == Predicate.Operator.EQUAL_TO) {
                    int predId = model.getPredicateId(p);

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


    public static class NumericBatchEvaluator {
        private final int fieldId;
        private final PredicateGroup[] groups;
        private final MemorySegment alignedWorkspace;
        private final float[] thresholdCache;

        private long vectorOps = 0;
        private long scalarOps = 0;

        public NumericBatchEvaluator(int fieldId, List<NumericPredicate> predicates) {
            this.fieldId = fieldId;
            this.groups = organizeIntoGroups(predicates);

            int maxGroupSize = Arrays.stream(groups)
                    .mapToInt(g -> g.predicates.length)
                    .max().orElse(0);

            this.alignedWorkspace = ARENA.allocate(
                    ((long) maxGroupSize * 4 + CACHE_LINE_SIZE - 1) / CACHE_LINE_SIZE * CACHE_LINE_SIZE
            );

            int paddedSize = ((maxGroupSize + FLOAT_SPECIES.length() - 1) / FLOAT_SPECIES.length())
                    * FLOAT_SPECIES.length();
            this.thresholdCache = new float[paddedSize];
        }

        public IntSet evaluateFloat16(float eventValue, IntSet eligiblePredicateIds) {
            IntSet matches = new IntOpenHashSet();

            for (PredicateGroup group : groups) {
                switch (group.operator) {
                    case GREATER_THAN:
                        evaluateGTFloat16Optimized(eventValue, group, matches, eligiblePredicateIds);
                        break;
                    case LESS_THAN:
                        evaluateLTFloat16Optimized(eventValue, group, matches, eligiblePredicateIds);
                        break;
                    case BETWEEN:
                        evaluateBetweenFloat16Optimized(eventValue, group, matches, eligiblePredicateIds);
                        break;
                    default:
                        evaluateScalar(eventValue, group, matches, eligiblePredicateIds);
                }
            }

            return matches;
        }

        private void evaluateGTFloat16Optimized(float eventValue, PredicateGroup group,
                                                IntSet matches, IntSet eligiblePredicateIds) {
            int count = group.predicates.length;

            if (count >= FLOAT_SPECIES.length() * 2) {
                vectorOps++;

                for (int i = 0; i < count; i++) {
                    thresholdCache[i] = (float) group.predicates[i].value;
                }
                Arrays.fill(thresholdCache, count, thresholdCache.length, 0.0f);

                long[] eligibilityMask = buildEligibilityMask(group, count, eligiblePredicateIds);

                FloatVector eventVec = FloatVector.broadcast(FLOAT_SPECIES, eventValue);

                int vectorCount = count / FLOAT_SPECIES.length();
                int remainder = count % FLOAT_SPECIES.length();

                for (int i = 0; i < vectorCount; i++) {
                    int offset = i * FLOAT_SPECIES.length();

                    FloatVector thresholdVec = FloatVector.fromArray(
                            FLOAT_SPECIES, thresholdCache, offset
                    );

                    VectorMask<Float> compareMask = eventVec.compare(
                            VectorOperators.GT, thresholdVec);

                    if (eligibilityMask != null) {
                        processMaskedResults(offset, compareMask, eligibilityMask,
                                group.predicates, matches);
                    } else {
                        for (int j = 0; j < FLOAT_SPECIES.length(); j++) {
                            if (compareMask.laneIsSet(j)) {
                                matches.add(group.predicates[offset + j].id);
                            }
                        }
                    }
                }

                if (remainder > 0) {
                    int start = vectorCount * FLOAT_SPECIES.length();
                    processScalarRemainder(start, count, eventValue, group,
                            eligibilityMask, matches, true);
                }
            } else {
                evaluateScalarGT(eventValue, group, matches, eligiblePredicateIds);
            }
        }

        private void evaluateLTFloat16Optimized(float eventValue, PredicateGroup group,
                                                IntSet matches, IntSet eligiblePredicateIds) {
            int count = group.predicates.length;

            if (count >= FLOAT_SPECIES.length() * 2) {
                vectorOps++;

                for (int i = 0; i < count; i++) {
                    thresholdCache[i] = (float) group.predicates[i].value;
                }
                Arrays.fill(thresholdCache, count, thresholdCache.length, 0.0f);

                long[] eligibilityMask = buildEligibilityMask(group, count, eligiblePredicateIds);

                FloatVector eventVec = FloatVector.broadcast(FLOAT_SPECIES, eventValue);

                int vectorCount = count / FLOAT_SPECIES.length();
                int remainder = count % FLOAT_SPECIES.length();

                for (int i = 0; i < vectorCount; i++) {
                    int offset = i * FLOAT_SPECIES.length();

                    FloatVector thresholdVec = FloatVector.fromArray(
                            FLOAT_SPECIES, thresholdCache, offset
                    );

                    VectorMask<Float> compareMask = eventVec.compare(
                            VectorOperators.LT, thresholdVec);

                    if (eligibilityMask != null) {
                        processMaskedResults(offset, compareMask, eligibilityMask,
                                group.predicates, matches);
                    } else {
                        for (int j = 0; j < FLOAT_SPECIES.length(); j++) {
                            if (compareMask.laneIsSet(j)) {
                                matches.add(group.predicates[offset + j].id);
                            }
                        }
                    }
                }

                if (remainder > 0) {
                    int start = vectorCount * FLOAT_SPECIES.length();
                    processScalarRemainder(start, count, eventValue, group,
                            eligibilityMask, matches, false);
                }
            } else {
                evaluateScalarLT(eventValue, group, matches, eligiblePredicateIds);
            }
        }

        private void evaluateBetweenFloat16Optimized(float eventValue, PredicateGroup group,
                                                     IntSet matches, IntSet eligiblePredicateIds) {
            int count = group.predicates.length;

            if (count >= FLOAT_SPECIES.length()) {
                vectorOps++;

                long[] eligibilityMask = buildEligibilityMask(group, count, eligiblePredicateIds);

                FloatVector eventVec = FloatVector.broadcast(FLOAT_SPECIES, eventValue);

                int vectorCount = count / FLOAT_SPECIES.length();
                int remainder = count % FLOAT_SPECIES.length();

                for (int i = 0; i < vectorCount; i++) {
                    int offset = i * FLOAT_SPECIES.length();

                    float[] lowerBounds = new float[FLOAT_SPECIES.length()];
                    float[] upperBounds = new float[FLOAT_SPECIES.length()];

                    for (int j = 0; j < FLOAT_SPECIES.length(); j++) {
                        lowerBounds[j] = (float) group.predicates[offset + j].value;
                        upperBounds[j] = (float) group.predicates[offset + j].value2;
                    }

                    FloatVector lowerVec = FloatVector.fromArray(FLOAT_SPECIES, lowerBounds, 0);
                    FloatVector upperVec = FloatVector.fromArray(FLOAT_SPECIES, upperBounds, 0);

                    VectorMask<Float> lowerMask = eventVec.compare(VectorOperators.GE, lowerVec);
                    VectorMask<Float> upperMask = eventVec.compare(VectorOperators.LE, upperVec);
                    VectorMask<Float> combinedMask = lowerMask.and(upperMask);

                    if (eligibilityMask != null) {
                        processMaskedResults(offset, combinedMask, eligibilityMask,
                                group.predicates, matches);
                    } else {
                        for (int j = 0; j < FLOAT_SPECIES.length(); j++) {
                            if (combinedMask.laneIsSet(j)) {
                                matches.add(group.predicates[offset + j].id);
                            }
                        }
                    }
                }

                if (remainder > 0) {
                    int start = vectorCount * FLOAT_SPECIES.length();
                    for (int i = start; i < count; i++) {
                        boolean eligible = eligibilityMask == null ||
                                ((eligibilityMask[i / 64] & (1L << (i % 64))) != 0);

                        if (eligible && eventValue >= group.predicates[i].value &&
                                eventValue <= group.predicates[i].value2) {
                            matches.add(group.predicates[i].id);
                        }
                    }
                }
            } else {
                evaluateScalarBetween(eventValue, group, matches, eligiblePredicateIds);
            }
        }

        private long[] buildEligibilityMask(PredicateGroup group, int count,
                                            IntSet eligiblePredicateIds) {
            if (eligiblePredicateIds == null) {
                return null;
            }

            long[] mask = new long[(count + 63) / 64];
            for (int i = 0; i < count; i++) {
                if (eligiblePredicateIds.contains(group.predicates[i].id)) {
                    mask[i / 64] |= (1L << (i % 64));
                }
            }
            return mask;
        }

        private void processMaskedResults(int offset, VectorMask<Float> compareMask,
                                          long[] eligibilityMask, NumericPredicate[] predicates,
                                          IntSet matches) {
            for (int j = 0; j < FLOAT_SPECIES.length(); j++) {
                int globalIdx = offset + j;
                int wordIdx = globalIdx / 64;
                int bitIdx = globalIdx % 64;

                boolean eligible = (eligibilityMask[wordIdx] & (1L << bitIdx)) != 0;

                if (eligible && compareMask.laneIsSet(j)) {
                    matches.add(predicates[globalIdx].id);
                }
            }
        }

        private void processScalarRemainder(int start, int end, float eventValue,
                                            PredicateGroup group, long[] eligibilityMask,
                                            IntSet matches, boolean isGreaterThan) {
            for (int i = start; i < end; i++) {
                boolean eligible = eligibilityMask == null ||
                        ((eligibilityMask[i / 64] & (1L << (i % 64))) != 0);

                if (eligible) {
                    boolean passes = isGreaterThan ?
                            eventValue > group.predicates[i].value :
                            eventValue < group.predicates[i].value;

                    if (passes) {
                        matches.add(group.predicates[i].id);
                    }
                }
            }
        }

        private void evaluateScalarGT(float eventValue, PredicateGroup group, IntSet matches,
                                      IntSet eligiblePredicateIds) {
            scalarOps += group.predicates.length;

            int i = 0;
            int count = group.predicates.length;

            for (; i + 4 <= count; i += 4) {
                processScalarPredicate(i, eventValue, group, matches, eligiblePredicateIds, true);
                processScalarPredicate(i + 1, eventValue, group, matches, eligiblePredicateIds, true);
                processScalarPredicate(i + 2, eventValue, group, matches, eligiblePredicateIds, true);
                processScalarPredicate(i + 3, eventValue, group, matches, eligiblePredicateIds, true);
            }

            for (; i < count; i++) {
                processScalarPredicate(i, eventValue, group, matches, eligiblePredicateIds, true);
            }
        }

        private void evaluateScalarLT(float eventValue, PredicateGroup group, IntSet matches,
                                      IntSet eligiblePredicateIds) {
            scalarOps += group.predicates.length;
            for (NumericPredicate pred : group.predicates) {
                if (eligiblePredicateIds == null || eligiblePredicateIds.contains(pred.id)) {
                    if (eventValue < pred.value) {
                        matches.add(pred.id);
                    }
                }
            }
        }

        private void evaluateScalarBetween(float eventValue, PredicateGroup group, IntSet matches,
                                           IntSet eligiblePredicateIds) {
            scalarOps += group.predicates.length * 2;
            for (NumericPredicate pred : group.predicates) {
                if (eligiblePredicateIds == null || eligiblePredicateIds.contains(pred.id)) {
                    if (eventValue >= pred.value && eventValue <= pred.value2) {
                        matches.add(pred.id);
                    }
                }
            }
        }

        private void processScalarPredicate(int idx, float eventValue, PredicateGroup group,
                                            IntSet matches, IntSet eligiblePredicateIds,
                                            boolean isGreaterThan) {
            NumericPredicate pred = group.predicates[idx];
            if (eligiblePredicateIds == null || eligiblePredicateIds.contains(pred.id)) {
                boolean passes = isGreaterThan ? eventValue > pred.value : eventValue < pred.value;
                if (passes) {
                    matches.add(pred.id);
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
            Map<Operator, List<NumericPredicate>> opGroups = new HashMap<>();

            for (NumericPredicate pred : predicates) {
                opGroups.computeIfAbsent(pred.operator, k -> new ArrayList<>()).add(pred);
            }

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

    public static class StringBatchEvaluator {
        private final int fieldId;
        private final StringPredicate[] predicates;
        private final Map<String, IntList> containsIndex;

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

            Set<Integer> candidates = new HashSet<>();
            for (int i = 0; i < value.length() - 1; i++) {
                String bigram = value.substring(i, i + 2);
                IntList predIds = containsIndex.get(bigram);
                if (predIds != null) {
                    predIds.forEach((IntConsumer) candidates::add);
                }
            }

            for (StringPredicate pred : predicates) {
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
                Arrays.fill(buffer, 0);
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
        final double value2;

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