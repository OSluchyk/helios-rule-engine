package com.helios.ruleengine.infra.cache;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Critical correctness tests for the hybrid FastCacheKeyGenerator.
 *
 * These tests verify that both the simple and complex paths produce
 * semantically equivalent keys for the same input data.
 */
public class FastCacheKeyGeneratorTest {

    /**
     * CRITICAL TEST: Verifies that keys are consistent across path boundaries.
     *
     * This test caught the original bug where the simple path included all
     * event attributes while the complex path only included matching ones.
     */
    @Test
    public void testHybridConsistency_SameRelevantData() {
        int[] predicates = {1, 2, 3};

        // Event 1: Only relevant attributes (triggers simple path)
        Int2ObjectMap<Object> smallEvent = new Int2ObjectOpenHashMap<>();
        smallEvent.put(1, (Object) 100);
        smallEvent.put(2, (Object) 200);
        smallEvent.put(3, (Object) 300);

        // Event 2: Same relevant data + many irrelevant attributes (triggers complex path)
        Int2ObjectMap<Object> largeEvent = new Int2ObjectOpenHashMap<>();
        largeEvent.put(1, (Object) 100);
        largeEvent.put(2, (Object) 200);
        largeEvent.put(3, (Object) 300);
        // Add 50 irrelevant attributes to force complex path
        for (int i = 100; i < 150; i++) {
            largeEvent.put(i, (Object) 999);
        }

        String key1 = FastCacheKeyGenerator.generateKey(smallEvent, predicates, 3);
        String key2 = FastCacheKeyGenerator.generateKey(largeEvent, predicates, 3);

        assertEquals(key1, key2,
                "Keys must match for same predicate set and matching attribute values, " +
                        "regardless of irrelevant attributes in the event!");
    }

    /**
     * Test that different relevant data produces different keys.
     */
    @Test
    public void testHybridConsistency_DifferentRelevantData() {
        int[] predicates = {1, 2, 3};

        Int2ObjectMap<Object> event1 = new Int2ObjectOpenHashMap<>();
        event1.put(1, (Object) 100);
        event1.put(2, (Object) 200);
        event1.put(3, (Object) 300);

        Int2ObjectMap<Object> event2 = new Int2ObjectOpenHashMap<>();
        event2.put(1, (Object) 100);
        event2.put(2, (Object) 200);
        event2.put(3, (Object) 999); // Different value for predicate 3

        String key1 = FastCacheKeyGenerator.generateKey(event1, predicates, 3);
        String key2 = FastCacheKeyGenerator.generateKey(event2, predicates, 3);

        assertNotEquals(key1, key2,
                "Keys must differ when relevant attribute values differ!");
    }

    /**
     * Test that different predicate sets produce different keys.
     */
    @Test
    public void testHybridConsistency_DifferentPredicateSets() {
        Int2ObjectMap<Object> event = new Int2ObjectOpenHashMap<>();
        event.put(1, (Object) 100);
        event.put(2, (Object) 200);
        event.put(3, (Object) 300);

        int[] predicates1 = {1, 2};
        int[] predicates2 = {1, 3};

        String key1 = FastCacheKeyGenerator.generateKey(event, predicates1, 2);
        String key2 = FastCacheKeyGenerator.generateKey(event, predicates2, 2);

        assertNotEquals(key1, key2,
                "Keys must differ for different predicate sets!");
    }

    /**
     * Test path selection threshold.
     */
    @Test
    public void testPathSelection() {
        Int2ObjectMap<Object> event = new Int2ObjectOpenHashMap<>();
        for (int i = 0; i < 20; i++) {
            event.put(i,(Object) (i * 10));
        }

        // Small predicate count → simple path
        int[] smallPredicates = {1, 2, 3};
        String key1 = FastCacheKeyGenerator.generateKey(event, smallPredicates, 3);
        assertNotNull(key1);
        assertEquals(16, key1.length(), "Key should be 16 characters");

        // Large predicate count → complex path
        int[] largePredicates = new int[20];
        for (int i = 0; i < 20; i++) {
            largePredicates[i] = i;
        }
        String key2 = FastCacheKeyGenerator.generateKey(event, largePredicates, 20);
        assertNotNull(key2);
        assertEquals(16, key2.length(), "Key should be 16 characters");
    }

    /**
     * Test missing attributes (null values).
     */
    @Test
    public void testMissingAttributes() {
        int[] predicates = {1, 2, 3, 4, 5};

        // Event with some missing attributes
        Int2ObjectMap<Object> event1 = new Int2ObjectOpenHashMap<>();
        event1.put(1, (Object) 100);
        event1.put(3, (Object) 300);
        event1.put(5, (Object) 500);
        // Predicates 2 and 4 are missing

        // Same predicates, same present values
        Int2ObjectMap<Object> event2 = new Int2ObjectOpenHashMap<>();
        event2.put(1, (Object) 100);
        event2.put(3,(Object)  300);
        event2.put(5,(Object)  500);
        event2.put(999, (Object) 999); // Extra irrelevant attribute

        String key1 = FastCacheKeyGenerator.generateKey(event1, predicates, 5);
        String key2 = FastCacheKeyGenerator.generateKey(event2, predicates, 5);

        assertEquals(key1, key2,
                "Keys must match when the same predicates have the same values, " +
                        "even with missing predicates and irrelevant attributes!");
    }

    /**
     * Test different value types.
     */
    @Test
    public void testDifferentValueTypes() {
        int[] predicates = {1, 2, 3, 4};

        Int2ObjectMap<Object> event = new Int2ObjectOpenHashMap<>();
        event.put(1, (Object) 100);              // Integer
        event.put(2, (Object) 200L);             // Long
        event.put(3, (Object) 3.14);             // Double
        event.put(4, "test");           // String

        String key = FastCacheKeyGenerator.generateKey(event, predicates, 4);
        assertNotNull(key);
        assertEquals(16, key.length());

        // Same data should produce same key
        Int2ObjectMap<Object> event2 = new Int2ObjectOpenHashMap<>();
        event2.put(1, (Object) 100);
        event2.put(2, (Object) 200L);
        event2.put(3,(Object)  3.14);
        event2.put(4, "test");

        String key2 = FastCacheKeyGenerator.generateKey(event2, predicates, 4);
        assertEquals(key, key2, "Same typed values must produce same key!");
    }

    /**
     * Test that string values are handled consistently.
     */
    @Test
    public void testStringValueConsistency() {
        int[] predicates = {1};

        Int2ObjectMap<Object> event1 = new Int2ObjectOpenHashMap<>();
        event1.put(1, "testString");

        Int2ObjectMap<Object> event2 = new Int2ObjectOpenHashMap<>();
        event2.put(1, "testString");

        String key1 = FastCacheKeyGenerator.generateKey(event1, predicates, 1);
        String key2 = FastCacheKeyGenerator.generateKey(event2, predicates, 1);

        assertEquals(key1, key2, "Same string values must produce same key!");

        // Different string should produce different key
        Int2ObjectMap<Object> event3 = new Int2ObjectOpenHashMap<>();
        event3.put(1, "differentString");
        String key3 = FastCacheKeyGenerator.generateKey(event3, predicates, 1);

        assertNotEquals(key1, key3, "Different strings must produce different keys!");
    }

    /**
     * Test base condition key generation.
     */
    @Test
    public void testBaseConditionKey() {
        int[] predicates = {1, 2, 3};
        int[] values = {100, 200, 300};

        String key1 = FastCacheKeyGenerator.generateBaseConditionKey(predicates, values, 3);
        String key2 = FastCacheKeyGenerator.generateBaseConditionKey(predicates, values, 3);

        assertEquals(key1, key2, "Base condition keys must be deterministic!");
        assertEquals(8, key1.length(), "Base condition key should be 8 characters");
    }

    /**
     * Test empty predicate set.
     */
    @Test
    public void testEmptyPredicateSet() {
        int[] predicates = {};
        Int2ObjectMap<Object> event = new Int2ObjectOpenHashMap<>();
        event.put(1, (Object) 100);
        event.put(2, (Object) 200);

        String key = FastCacheKeyGenerator.generateKey(event, predicates, 0);
        assertNotNull(key);
        assertEquals(16, key.length());
    }

    /**
     * Stress test: Many predicates and attributes.
     */
    @Test
    public void testLargeInput() {
        int[] predicates = new int[100];
        Int2ObjectMap<Object> event = new Int2ObjectOpenHashMap<>();

        for (int i = 0; i < 100; i++) {
            predicates[i] = i;
            event.put(i, (Object) (i * 10));
        }

        String key = FastCacheKeyGenerator.generateKey(event, predicates, 100);
        assertNotNull(key);
        assertEquals(16, key.length());
    }

    /**
     * Performance comparison test (informational).
     */
    @Test
    public void testPerformanceProfile() {
        int[] smallPredicates = {1, 2, 3};
        int[] largePredicates = new int[50];
        for (int i = 0; i < 50; i++) {
            largePredicates[i] = i;
        }

        Int2ObjectMap<Object> event = new Int2ObjectOpenHashMap<>();
        for (int i = 0; i < 50; i++) {
            event.put(i, (Object) (i * 10));
        }

        // Warmup
        for (int i = 0; i < 1000; i++) {
            FastCacheKeyGenerator.generateKey(event, smallPredicates, 3);
            FastCacheKeyGenerator.generateKey(event, largePredicates, 50);
        }

        // Time small (simple path)
        long start1 = System.nanoTime();
        for (int i = 0; i < 100000; i++) {
            FastCacheKeyGenerator.generateKey(event, smallPredicates, 3);
        }
        long elapsed1 = System.nanoTime() - start1;

        // Time large (complex path)
        long start2 = System.nanoTime();
        for (int i = 0; i < 100000; i++) {
            FastCacheKeyGenerator.generateKey(event, largePredicates, 50);
        }
        long elapsed2 = System.nanoTime() - start2;

        System.out.printf("Simple path: %.1f ns/op%n", elapsed1 / 100000.0);
        System.out.printf("Complex path: %.1f ns/op%n", elapsed2 / 100000.0);
        System.out.printf("Speedup: %.2fx%n", (double)elapsed2 / elapsed1);

        // Simple path should be faster
        assertTrue(elapsed1 < elapsed2,
                "Simple path should be faster than complex path for small inputs!");
    }
}