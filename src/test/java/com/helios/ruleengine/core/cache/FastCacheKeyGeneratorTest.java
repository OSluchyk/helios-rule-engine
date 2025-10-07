package com.helios.ruleengine.core.cache;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.assertThat;

class FastCacheKeyGeneratorTest {
    @Test
    void testExtremePredicateSet() {
        // Simulate COMPLEX benchmark rule
        int[] predicateIds = IntStream.range(0, 5000).toArray();
        Int2ObjectMap<Object> attrs = new Int2ObjectOpenHashMap<>();

        // Sparse event: only 50 attributes
        for (int i = 0; i < 50; i++) {
            attrs.put(i, "value_" + i);
        }

        String key = FastCacheKeyGenerator.generateKey(attrs, predicateIds, 5000);

        assertThat(key).hasSize(16);
        assertThat(key).matches("[A-Za-z0-9_-]{16}"); // Base64-like
    }

    @Test
    void testHashCollisionResistance() {
        // Generate 10,000 different predicate sets
        Set<String> keys = new HashSet<>();

        for (int i = 0; i < 10_000; i++) {
            int[] predicateIds = IntStream.range(i * 10, i * 10 + 100).toArray();
            Int2ObjectMap<Object> attrs = new Int2ObjectOpenHashMap<>();
            attrs.put(0, Optional.of(i));

            String key = FastCacheKeyGenerator.generateKey(attrs, predicateIds, 100);
            keys.add(key);
        }

        // No collisions expected
        assertThat(keys).hasSize(10_000);
    }

}