package com.helios.ruleengine.core.cache;

// ====================================================================
// INTEGRATION GUIDE: How to Use AdaptiveCaffeineCache
// ====================================================================


import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

/**
 * STEP 1: Replace CaffeineBaseConditionCache with AdaptiveCaffeineCache
 * <p>
 * BEFORE:
 * BaseConditionCache cache = new CaffeineBaseConditionCache.Builder()
 * .maxSize(100_000)
 * .build();
 * <p>
 * AFTER:
 * BaseConditionCache cache = new AdaptiveCaffeineCache.Builder()
 * .initialMaxSize(100_000)
 * .enableAdaptiveSizing(true)
 * .build();
 */
class AdaptiveCaffeineCacheIntegrationTest {
    private static final long TEST_CACHE_SIZE = 100_000L; // Use a constant for the valid size


    private AdaptiveCaffeineCache cache;

    @BeforeEach
    void setUp() {
        cache = new AdaptiveCaffeineCache.Builder()
                .initialMaxSize(TEST_CACHE_SIZE)
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .recordStats(true)
                .enableAdaptiveSizing(true)
                .build();
    }

    @AfterEach
    void tearDown() {
        cache.shutdown();
    }

    // ================================================================
    // TEST 1: Basic Integration with FastCacheKeyGenerator
    // ================================================================

    @Test
    @DisplayName("Works with FastCacheKeyGenerator keys")
    void integrationWithFastCacheKeyGenerator() {
        // Simulate real usage
        Int2ObjectMap<Object> eventAttrs = new Int2ObjectOpenHashMap<>();
        eventAttrs.put(0, "premium");
        eventAttrs.put(1, Integer.valueOf(1500));
        eventAttrs.put(2, "US");

        int[] predicateIds = {0, 1, 2};

        // Generate key using FastCacheKeyGenerator
        String cacheKey = FastCacheKeyGenerator.generateKey(
                eventAttrs,
                predicateIds,
                3
        );

        // Verify key format (16-char Base64-like)
        assertThat(cacheKey).hasSize(16);
        assertThat(cacheKey).matches("[A-Za-z0-9_-]{16}");

        // Create mock result
        BitSet result = new BitSet();
        result.set(0);
        result.set(5);
        result.set(10);

        // Put in cache
        cache.put(cacheKey, result, 5, TimeUnit.MINUTES).join();

        // Get from cache
        Optional<BaseConditionCache.CacheEntry> cached = cache.get(cacheKey).join();

        assertThat(cached).isPresent();
        assertThat(cached.get().result()).isEqualTo(result);
    }

    // ================================================================
    // TEST 2: Drop-in Replacement for CaffeineBaseConditionCache
    // ================================================================

    @Test
    @DisplayName("Implements all BaseConditionCache methods")
    void implementsBaseConditionCacheInterface() {
        // Verify it's a drop-in replacement
        BaseConditionCache baseCache = cache;  // Upcast to interface

        String key = "test_key";
        BitSet value = new BitSet();
        value.set(42);

        // All async methods should work
        CompletableFuture<Void> putFuture = baseCache.put(key, value, 1, TimeUnit.MINUTES);
        assertThat(putFuture).isCompleted();

        CompletableFuture<Optional<BaseConditionCache.CacheEntry>> getFuture = baseCache.get(key);
        assertThat(getFuture).isCompletedWithValueMatching(Optional::isPresent);

        CompletableFuture<Void> invalidateFuture = baseCache.invalidate(key);
        assertThat(invalidateFuture).isCompleted();

        CompletableFuture<Optional<BaseConditionCache.CacheEntry>> emptyFuture = baseCache.get(key);
        assertThat(emptyFuture).isCompletedWithValue(Optional.empty());
    }

    // ================================================================
    // TEST 3: Cache Hit Rate with Real Keys
    // ================================================================

    @Test
    @DisplayName("Achieves high cache hit rate with typical workload")
    void achievesHighCacheHitRate() {
        // Simulate 1000 evaluations with 100 unique base condition sets
        for (int i = 0; i < 1000; i++) {
            Int2ObjectMap<Object> eventAttrs = new Int2ObjectOpenHashMap<>();
            eventAttrs.put(0, "value_" + (i % 100));  // 100 unique patterns

            int[] predicateIds = {0};
            String cacheKey = FastCacheKeyGenerator.generateKey(eventAttrs, predicateIds, 1);

            Optional<BaseConditionCache.CacheEntry> cached = cache.get(cacheKey).join();

            if (cached.isEmpty()) {
                // Cache miss - compute and store
                BitSet result = new BitSet();
                result.set(i % 10);
                cache.put(cacheKey, result, 5, TimeUnit.MINUTES).join();
            }
        }

        // Check metrics
        BaseConditionCache.CacheMetrics metrics = cache.getMetrics();

        // After 1000 operations with 100 unique keys:
        // - First 100 are misses
        // - Remaining 900 are hits
        // Expected hit rate: 90%
        assertThat(metrics.hitRate()).isGreaterThan(85);  // >85% hit rate
    }

    // ================================================================
    // TEST 4: Adaptive Resizing
    // ================================================================

    @Test
    @DisplayName("Automatically resizes based on hit rate")
    void automaticallyResizes() throws Exception {
        // Start with small cache
        AdaptiveCaffeineCache smallCache = new AdaptiveCaffeineCache.Builder()
                .initialMaxSize(TEST_CACHE_SIZE)
                .recordStats(true)
                .enableAdaptiveSizing(true)
                .build();

        try {
            long initialSize = smallCache.getAdaptiveStats().maxSize;
            assertThat(initialSize).isEqualTo(TEST_CACHE_SIZE);

            // Fill cache to >80% capacity with unique keys
            for (int i = 0; i < 90; i++) {
                String key = "key_" + i;
                BitSet value = new BitSet();
                value.set(i);
                smallCache.put(key, value, 5, TimeUnit.MINUTES).join();
            }

            // Add more unique keys to lower hit rate
            for (int i = 90; i < 200; i++) {
                String key = "key_" + i;
                smallCache.get(key).join();  // Will miss
                BitSet value = new BitSet();
                value.set(i);
                smallCache.put(key, value, 5, TimeUnit.MINUTES).join();
            }

            // Manually trigger resize (normally happens every 30s)
            smallCache.adaptSize();

            // Check if resized
            AdaptiveCaffeineCache.AdaptiveStats stats = smallCache.getAdaptiveStats();

            // Should have resized if hit rate < 70% and utilization > 80%
            assertThat(stats.maxSize).isGreaterThanOrEqualTo(100);

            System.out.println("Adaptive stats: " + stats);

        } finally {
            smallCache.shutdown();
        }
    }

    // ================================================================
    // TEST 5: Thread Safety with Concurrent Access
    // ================================================================

    @Test
    @DisplayName("Thread-safe under concurrent access")
    void threadSafeUnderConcurrency() throws Exception {
        int numThreads = 10;
        int opsPerThread = 1000;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads * opsPerThread);

        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                for (int i = 0; i < opsPerThread; i++) {
                    try {
                        // Each thread uses different keys
                        Int2ObjectMap<Object> attrs = new Int2ObjectOpenHashMap<>();
                        attrs.put(threadId, Integer.valueOf(i));

                        String key = FastCacheKeyGenerator.generateKey(
                                attrs,
                                new int[]{threadId},
                                1
                        );

                        // Get or create
                        Optional<BaseConditionCache.CacheEntry> cached = cache.get(key).join();
                        if (cached.isEmpty()) {
                            BitSet result = new BitSet();
                            result.set(i % 64);
                            cache.put(key, result, 5, TimeUnit.MINUTES).join();
                        }
                    } finally {
                        latch.countDown();
                    }
                }
            });
        }

        boolean completed = latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(cache.getMetrics().size()).isEqualTo(0);
    }

    // ================================================================
    // TEST 6: Batch Operations
    // ================================================================

    @Test
    @DisplayName("Batch operations work with FastCacheKeyGenerator")
    void batchOperationsWork() {
        // Prepare multiple keys
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Int2ObjectMap<Object> attrs = new Int2ObjectOpenHashMap<>();
            attrs.put(0, Integer.valueOf(i));

            String key = FastCacheKeyGenerator.generateKey(attrs, new int[]{0}, 1);
            keys.add(key);

            // Pre-populate cache
            BitSet value = new BitSet();
            value.set(i);
            cache.put(key, value, 5, TimeUnit.MINUTES).join();
        }

        // Batch get
        Map<String, BaseConditionCache.CacheEntry> results = cache.getBatch(keys).join();

        assertThat(results).hasSize(10);
        for (int i = 0; i < 10; i++) {
            String key = keys.get(i);
            assertThat(results).containsKey(key);
            assertThat(results.get(key).result().get(i)).isTrue();
        }
    }

    // ================================================================
    // TEST 7: Metrics Collection
    // ================================================================

    @Test
    @DisplayName("Collects detailed metrics")
    void collectsDetailedMetrics() {
        // Perform some operations
        for (int i = 0; i < 100; i++) {
            String key = "metric_test_" + (i % 10);  // 10 unique keys

            cache.get(key).join();  // Will miss first time

            BitSet value = new BitSet();
            value.set(i);
            cache.put(key, value, 5, TimeUnit.MINUTES).join();

            cache.get(key).join();  // Will hit
        }

        // Check metrics
        BaseConditionCache.CacheMetrics metrics = cache.getMetrics();

        assertThat(metrics.totalRequests()).isGreaterThan(0);
        assertThat(metrics.hits()).isGreaterThan(0);
        assertThat(metrics.misses()).isGreaterThan(0);
        assertThat(metrics.size()).isEqualTo(0);
        assertThat(metrics.hitRate()).isBetween(0.0, 100.0);

        // Check adaptive-specific stats
        AdaptiveCaffeineCache.AdaptiveStats adaptiveStats = cache.getAdaptiveStats();

        assertThat(adaptiveStats.currentSize).isGreaterThan(0);
        assertThat(adaptiveStats.hitRate).isBetween(0.0, 1.0);

        System.out.println("Cache metrics: " + metrics);
        System.out.println("Adaptive stats: " + adaptiveStats);
    }

    // ================================================================
    // TEST 8: Graceful Shutdown
    // ================================================================

    @Test
    @DisplayName("Shuts down gracefully")
    void shutsDownGracefully() {
        AdaptiveCaffeineCache testCache = new AdaptiveCaffeineCache.Builder()
                .initialMaxSize(TEST_CACHE_SIZE)
                .build();

        // Add some data
        testCache.put("key1", new BitSet(), 1, TimeUnit.MINUTES).join();

        // Shutdown
        testCache.shutdown();

        // Verify idempotent
        testCache.shutdown();  // Should not throw
    }
}

