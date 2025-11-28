package com.helios.ruleengine.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RMapCache;
import org.redisson.api.RedissonClient;
import org.roaringbitmap.RoaringBitmap;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@org.junit.jupiter.api.Disabled("Skipped due to Java 25 / ByteBuddy incompatibility")
class RedisBaseConditionCacheTest {

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RMapCache<String, byte[]> cacheMap;

    private RedisBaseConditionCache cache;

    @BeforeEach
    void setUp() {
        when(redissonClient.<String, byte[]>getMapCache(anyString())).thenReturn(cacheMap);
        // Initialize with compression disabled for simpler testing
        cache = new RedisBaseConditionCache(redissonClient, 0);
    }

    @Test
    @DisplayName("Should put value into Redis")
    void shouldPutValue() {
        Object key = "key1";
        RoaringBitmap value = new RoaringBitmap();
        value.add(1);

        cache.put(key, value, 1, TimeUnit.MINUTES).join();

        ArgumentCaptor<byte[]> valueCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(cacheMap).put(eq("key1"), valueCaptor.capture(), eq(1L), eq(TimeUnit.MINUTES));

        // Verify serialized content
        byte[] capturedValue = valueCaptor.getValue();
        assertThat(capturedValue).isNotEmpty();
    }

    @Test
    @DisplayName("Should get value from Redis")
    void shouldGetValue() {
        Object key = "key1";
        RoaringBitmap value = new RoaringBitmap();
        value.add(1);

        // Serialize manually for mock return
        byte[] serialized = serialize(value);
        when(cacheMap.get("key1")).thenReturn(serialized);

        Optional<BaseConditionCache.CacheEntry> result = cache.get(key).join();

        assertThat(result).isPresent();
        assertThat(result.get().result()).isEqualTo(value);
    }

    @Test
    @DisplayName("Should return empty for missing key")
    void shouldReturnEmptyForMissingKey() {
        when(cacheMap.get("missing")).thenReturn(null);

        Optional<BaseConditionCache.CacheEntry> result = cache.get("missing").join();

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Should get batch of values")
    void shouldGetBatch() {
        RoaringBitmap v1 = new RoaringBitmap();
        v1.add(1);
        RoaringBitmap v2 = new RoaringBitmap();
        v2.add(2);

        when(cacheMap.getAll(any(Set.class))).thenReturn(Map.of(
                "k1", serialize(v1),
                "k2", serialize(v2)));

        Map<Object, BaseConditionCache.CacheEntry> batch = cache.getBatch(List.of("k1", "k2")).join();

        assertThat(batch).hasSize(2);
        assertThat(batch.get("k1").result()).isEqualTo(v1);
        assertThat(batch.get("k2").result()).isEqualTo(v2);
    }

    @Test
    @DisplayName("Should invalidate key")
    void shouldInvalidateKey() {
        cache.invalidate("key1").join();
        verify(cacheMap).fastRemove("key1");
    }

    @Test
    @DisplayName("Should clear cache")
    void shouldClearCache() {
        cache.clear().join();
        verify(cacheMap).clear();
    }

    @Test
    @DisplayName("Should compress large values if enabled")
    void shouldCompressLargeValues() {
        // Re-init with compression enabled (threshold 10 bytes)
        cache = new RedisBaseConditionCache(redissonClient, 10);

        Object key = "largeKey";
        RoaringBitmap value = new RoaringBitmap();
        // Add enough data to exceed 10 bytes
        for (int i = 0; i < 1000; i++) {
            value.add(i * 100);
        }

        cache.put(key, value, 1, TimeUnit.MINUTES).join();

        ArgumentCaptor<byte[]> valueCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(cacheMap).put(eq("largeKey"), valueCaptor.capture(), eq(1L), eq(TimeUnit.MINUTES));

        byte[] capturedValue = valueCaptor.getValue();
        // Check for GZIP magic bytes (1F 8B)
        assertThat(capturedValue.length).isGreaterThan(2);
        assertThat(capturedValue[0]).isEqualTo((byte) 0x1F);
        assertThat(capturedValue[1]).isEqualTo((byte) 0x8B);
    }

    // Helper to serialize RoaringBitmap for mocks
    private byte[] serialize(RoaringBitmap bitmap) {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(bitmap.serializedSizeInBytes());
        bitmap.serialize(buffer);
        return buffer.array();
    }
}
