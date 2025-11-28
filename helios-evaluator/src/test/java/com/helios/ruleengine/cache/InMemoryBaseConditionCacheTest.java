package com.helios.ruleengine.cache;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.roaringbitmap.RoaringBitmap;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryBaseConditionCacheTest {

    private InMemoryBaseConditionCache cache;

    @BeforeEach
    void setUp() {
        // Create a cache with small size and short TTL for testing
        cache = InMemoryBaseConditionCache.builder()
                .maxSize(10)
                .defaultTtl(100, TimeUnit.MILLISECONDS)
                .enableBackgroundCleanup(false) // Disable background cleanup for deterministic testing
                .build();
    }

    @AfterEach
    void tearDown() {
        cache.shutdown();
    }

    @Test
    @DisplayName("Should put and get value")
    void shouldPutAndGetValue() {
        Object key = "key1";
        RoaringBitmap value = new RoaringBitmap();
        value.add(1);

        cache.put(key, value, 1, TimeUnit.MINUTES).join();

        Optional<BaseConditionCache.CacheEntry> result = cache.get(key).join();
        assertThat(result).isPresent();
        assertThat(result.get().result()).isEqualTo(value);
    }

    @Test
    @DisplayName("Should return empty for missing key")
    void shouldReturnEmptyForMissingKey() {
        Optional<BaseConditionCache.CacheEntry> result = cache.get("missing").join();
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Should expire value after TTL")
    void shouldExpireValueAfterTTL() throws InterruptedException {
        Object key = "key1";
        RoaringBitmap value = new RoaringBitmap();
        value.add(1);

        // Put with very short TTL
        cache.put(key, value, 10, TimeUnit.MILLISECONDS).join();

        // Wait for expiration
        Thread.sleep(20);

        Optional<BaseConditionCache.CacheEntry> result = cache.get(key).join();
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Should evict LRU when max size reached")
    void shouldEvictLRU() {
        // Re-initialize with size 2
        cache = InMemoryBaseConditionCache.builder()
                .maxSize(2)
                .defaultTtl(1, TimeUnit.MINUTES)
                .enableBackgroundCleanup(false)
                .build();

        RoaringBitmap value = new RoaringBitmap();

        cache.put("k1", value, 1, TimeUnit.MINUTES).join();
        cache.put("k2", value, 1, TimeUnit.MINUTES).join();

        // Access k1 to make it recently used
        cache.get("k1").join();

        // Add k3, which should evict k2 (LRU)
        cache.put("k3", value, 1, TimeUnit.MINUTES).join();

        assertThat(cache.get("k1").join()).isPresent();
        assertThat(cache.get("k3").join()).isPresent();
        assertThat(cache.get("k2").join()).isEmpty(); // k2 should be evicted
    }

    @Test
    @DisplayName("Should get batch of values")
    void shouldGetBatch() {
        RoaringBitmap v1 = new RoaringBitmap();
        v1.add(1);
        RoaringBitmap v2 = new RoaringBitmap();
        v2.add(2);

        cache.put("k1", v1, 1, TimeUnit.MINUTES).join();
        cache.put("k2", v2, 1, TimeUnit.MINUTES).join();

        Map<Object, BaseConditionCache.CacheEntry> batch = cache.getBatch(List.of("k1", "k2", "missing")).join();

        assertThat(batch).hasSize(2);
        assertThat(batch.get("k1").result()).isEqualTo(v1);
        assertThat(batch.get("k2").result()).isEqualTo(v2);
    }

    @Test
    @DisplayName("Should invalidate key")
    void shouldInvalidateKey() {
        Object key = "key1";
        RoaringBitmap value = new RoaringBitmap();
        cache.put(key, value, 1, TimeUnit.MINUTES).join();

        cache.invalidate(key).join();

        assertThat(cache.get(key).join()).isEmpty();
    }

    @Test
    @DisplayName("Should clear cache")
    void shouldClearCache() {
        cache.put("k1", new RoaringBitmap(), 1, TimeUnit.MINUTES).join();
        cache.put("k2", new RoaringBitmap(), 1, TimeUnit.MINUTES).join();

        cache.clear().join();

        assertThat(cache.get("k1").join()).isEmpty();
        assertThat(cache.get("k2").join()).isEmpty();
    }

    @Test
    @DisplayName("Should record metrics")
    void shouldRecordMetrics() {
        cache.put("k1", new RoaringBitmap(), 1, TimeUnit.MINUTES).join();

        cache.get("k1").join(); // Hit
        cache.get("missing").join(); // Miss

        BaseConditionCache.CacheMetrics metrics = cache.getMetrics();
        assertThat(metrics.hits()).isEqualTo(1);
        assertThat(metrics.misses()).isEqualTo(1);
        assertThat(metrics.totalRequests()).isEqualTo(2);
        assertThat(metrics.hitRate()).isEqualTo(0.5);
    }
}
