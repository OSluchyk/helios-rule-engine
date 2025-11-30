package com.helios.ruleengine.cache;

import org.junit.jupiter.api.*;
import org.roaringbitmap.RoaringBitmap;

import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Minimal test for CaffeineBaseConditionCache.
 * Only tests basic get/put functionality.
 */
public class CaffeineBaseConditionCacheTest {

    private CaffeineBaseConditionCache cache;

    @BeforeEach
    void setup() {
        cache = CaffeineBaseConditionCache.builder()
                .maxSize(1000)
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .recordStats(true)
                .build();
    }

    @Test
    @DisplayName("Should store and retrieve entries")
    void testBasicPutGet() throws Exception {
        // Given
        String key = "test-key";
        RoaringBitmap value = new RoaringBitmap();
        value.add(10);
        value.add(20);

        // When
        cache.put(key, value, 5, TimeUnit.MINUTES).get();
        Optional<BaseConditionCache.CacheEntry> result = cache.get(key).get();

        // Then
        assertTrue(result.isPresent());
        RoaringBitmap retrieved = result.get().result();
        assertEquals(value, retrieved);
    }

    @Test
    @DisplayName("Should return empty for missing keys")
    void testGetMissing() throws Exception {
        // When
        Optional<BaseConditionCache.CacheEntry> result = cache.get("missing-key").get();

        // Then
        assertFalse(result.isPresent());
    }

    @Test
    @DisplayName("Should invalidate entries")
    void testInvalidate() throws Exception {
        // Given
        String key = "test-key";
        RoaringBitmap value = new RoaringBitmap();
        value.add(5);
        cache.put(key, value, 5, TimeUnit.MINUTES).get();

        // When
        cache.invalidate(key).get();
        Optional<BaseConditionCache.CacheEntry> result = cache.get(key).get();

        // Then
        assertFalse(result.isPresent());
    }

    @Test
    @DisplayName("Should clear all entries")
    void testClear() throws Exception {
        // Given
        cache.put("key1", new RoaringBitmap(), 5, TimeUnit.MINUTES).get();
        cache.put("key2", new RoaringBitmap(), 5, TimeUnit.MINUTES).get();

        // When
        cache.clear().get();

        // Then
        assertFalse(cache.get("key1").get().isPresent());
        assertFalse(cache.get("key2").get().isPresent());
    }

    @Test
    @DisplayName("Should track metrics")
    void testMetrics() throws Exception {
        // Given
        cache.put("key1", new RoaringBitmap(), 5, TimeUnit.MINUTES).get();

        // When - Generate hits and misses
        cache.get("key1").get(); // Hit
        cache.get("missing").get(); // Miss

        // Then
        BaseConditionCache.CacheMetrics metrics = cache.getMetrics();
        assertTrue(metrics.totalRequests() >= 2);
        assertTrue(metrics.hits() >= 1);
        assertTrue(metrics.misses() >= 1);
    }

    @Test
    @DisplayName("Should return shared reference (no defensive copy) for performance")
    void testSharedOwnership() throws Exception {
        // Given
        String key = "test-key";
        RoaringBitmap original = new RoaringBitmap();
        original.add(50);
        cache.put(key, original, 5, TimeUnit.MINUTES).get();

        // When - Get value
        Optional<BaseConditionCache.CacheEntry> entry1 = cache.get(key).get();

        // Then - Should be same instance (identity check)
        assertSame(original, entry1.get().result(), "Should return original instance to avoid allocation overhead");

        // When - Modify returned value (simulating what we want to avoid but checking
        // reference)
        entry1.get().result().add(75);

        // Then - Original in cache should be changed (because it's the same object)
        Optional<BaseConditionCache.CacheEntry> entry2 = cache.get(key).get();
        assertTrue(entry2.get().result().contains(75));
    }
}