package com.helios.ruleengine.core.evaluation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public class EvaluatorMetrics {
    private final AtomicLong totalEvaluations = new AtomicLong();
    private final AtomicLong totalTimeNanos = new AtomicLong();
    private final AtomicLong totalPredicatesEvaluated = new AtomicLong();
    private final AtomicLong totalRulesEvaluated = new AtomicLong();
    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong cacheMisses = new AtomicLong();
    final AtomicLong roaringConversionsSaved = new AtomicLong();
    final AtomicLong eligibleSetCacheHits = new AtomicLong();

    void recordEvaluation(long timeNanos, int predicatesEvaluated, int rulesEvaluated) {
        totalEvaluations.incrementAndGet();
        totalTimeNanos.addAndGet(timeNanos);
        totalPredicatesEvaluated.addAndGet(predicatesEvaluated);
        totalRulesEvaluated.addAndGet(rulesEvaluated);
    }

    public Map<String, Object> getSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        long evals = totalEvaluations.get();

        if (evals > 0) {
            snapshot.put("totalEvaluations", evals);
            snapshot.put("avgLatencyMicros", totalTimeNanos.get() / 1000 / evals);
            snapshot.put("avgPredicatesPerEvent", (double) totalPredicatesEvaluated.get() / evals);
            snapshot.put("avgRulesConsideredPerEvent", (double) totalRulesEvaluated.get() / evals);

            long hits = cacheHits.get();
            long misses = cacheMisses.get();
            if (hits + misses > 0) {
                snapshot.put("cacheHitRate", (double) hits / (hits + misses));
            }

            long conversionsSaved = roaringConversionsSaved.get();
            snapshot.put("roaringConversionsSaved", conversionsSaved);
            snapshot.put("conversionSavingsRate",
                    evals > 0 ? (double) conversionsSaved / evals * 100 : 0.0);

            long eligibleHits = eligibleSetCacheHits.get();
            snapshot.put("eligibleSetCacheHits", eligibleHits);
            snapshot.put("eligibleSetCacheHitRate",
                    evals > 0 ? (double) eligibleHits / evals * 100 : 0.0);
        }

        return snapshot;
    }
}
