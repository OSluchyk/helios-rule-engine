package com.helios.ruleengine.core.cache;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.roaringbitmap.RoaringBitmap;

import java.util.BitSet;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class PerformanceComparisonTest {

    @Test
    @DisplayName("Performance comparison: Adaptive vs Fixed")
    void comparePerformance() throws Exception {
        // Fixed-size cache
        BaseConditionCache fixedCache = new CaffeineBaseConditionCache.Builder()
                .maxSize(10_000)
                .build();

        // Adaptive cache
        AdaptiveCaffeineCache adaptiveCache = new AdaptiveCaffeineCache.Builder()
                .initialMaxSize(10_000)
                .enableAdaptiveSizing(true)
                .build();

        try {
            // Workload: 100K operations, gradually increasing unique keys
            long fixedStart = System.nanoTime();
            runWorkload(fixedCache, 100_000);
            long fixedDuration = System.nanoTime() - fixedStart;

            long adaptiveStart = System.nanoTime();
            runWorkload(adaptiveCache, 100_000);
            long adaptiveDuration = System.nanoTime() - adaptiveStart;

            System.out.println("Fixed cache duration:    " + (fixedDuration / 1_000_000) + "ms");
            System.out.println("Adaptive cache duration: " + (adaptiveDuration / 1_000_000) + "ms");

            BaseConditionCache.CacheMetrics fixedMetrics = fixedCache.getMetrics();
            BaseConditionCache.CacheMetrics adaptiveMetrics = adaptiveCache.getMetrics();

            System.out.println("Fixed hit rate:    " + fixedMetrics.hitRate() + "%");
            System.out.println("Adaptive hit rate: " + adaptiveMetrics.hitRate() + "%");

            // Adaptive should have similar or better hit rate
            assertThat(adaptiveMetrics.hitRate()).isGreaterThanOrEqualTo(fixedMetrics.hitRate() - 5);

        } finally {
            adaptiveCache.shutdown();
        }
    }

    private void runWorkload(BaseConditionCache cache, int operations) {
        for (int i = 0; i < operations; i++) {
            Int2ObjectMap<Object> attrs = new Int2ObjectOpenHashMap<>();
            attrs.put(0,  Double.valueOf(i % 1000));  // Gradually increasing unique keys

            String key = FastCacheKeyGenerator.generateKey(attrs, new int[]{0}, 1);

            Optional<BaseConditionCache.CacheEntry> cached = cache.get(key).join();
            if (cached.isEmpty()) {
                RoaringBitmap result = new RoaringBitmap();
                result.add(i % 64);
                cache.put(key, result, 5, TimeUnit.MINUTES).join();
            }
        }
    }
}
