/*
 * Copyright (c) 2025 Helios Rule Engine
 * Licensed under the Apache License, Version 2.0
 */
package com.helios.ruleengine.model;

import com.helios.ruleengine.core.model.Dictionary;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for Event string normalization caching optimization.
 *
 * These tests verify that the string normalization cache works correctly
 * to eliminate repeated toUpperCase() calls, which was the #1 CPU hotspot.
 */
class EventStringNormalizationTest {

    private Dictionary fieldDictionary;
    private Dictionary valueDictionary;

    @BeforeEach
    void setUp() {
        fieldDictionary = new Dictionary();
        valueDictionary = new Dictionary();

        // Pre-populate dictionaries with test fields and values
        fieldDictionary.encode("STATUS");
        fieldDictionary.encode("AMOUNT");
        fieldDictionary.encode("CUSTOMER_TIER");
        fieldDictionary.encode("DESCRIPTION");

        valueDictionary.encode("ACTIVE");
        valueDictionary.encode("PENDING");
        valueDictionary.encode("PLATINUM");
        valueDictionary.encode("GOLD");
    }

    @Test
    @DisplayName("Should uppercase string values on first encoding")
    void shouldUppercaseStringValuesOnFirstEncoding() {
        // Given
        Event event = new Event("evt-001", "TEST", Map.of(
                "status", "active",
                "customer_tier", "platinum"
        ));

        // When
        Int2ObjectMap<Object> encoded = event.getEncodedAttributes(fieldDictionary, valueDictionary);

        // Then - verify values were uppercased and encoded correctly
        int statusFieldId = fieldDictionary.getId("STATUS");
        int tierFieldId = fieldDictionary.getId("CUSTOMER_TIER");

        assertThat(encoded.containsKey(statusFieldId)).isTrue();
        assertThat(encoded.containsKey(tierFieldId)).isTrue();

        // Values should be dictionary IDs of uppercased strings
        int activeValueId = valueDictionary.getId("ACTIVE");
        int platinumValueId = valueDictionary.getId("PLATINUM");

        assertThat(encoded.get(statusFieldId)).isEqualTo(activeValueId);
        assertThat(encoded.get(tierFieldId)).isEqualTo(platinumValueId);
    }

    @Test
    @DisplayName("Should cache normalized strings for reuse")
    void shouldCacheNormalizedStringsForReuse() throws Exception {
        // Given
        Event event = new Event("evt-002", "TEST", Map.of(
                "status", "active",
                "customer_tier", "platinum"
        ));

        // When - first call initializes the cache
        event.getEncodedAttributes(fieldDictionary, valueDictionary);

        // Then - verify the cache was populated
        Map<String, String> cache = getNormalizedStringsCache(event);
        assertThat(cache).isNotNull();
        assertThat(cache).containsEntry("active", "ACTIVE");
        assertThat(cache).containsEntry("platinum", "PLATINUM");
    }

    @Test
    @DisplayName("Should reuse cached strings on subsequent calls")
    void shouldReuseCachedStringsOnSubsequentCalls() throws Exception {
        // Given
        Event event = new Event("evt-003", "TEST", Map.of(
                "status", "active",
                "customer_tier", "platinum"
        ));

        // When - multiple calls to getEncodedAttributes
        event.getEncodedAttributes(fieldDictionary, valueDictionary);
        event.getEncodedAttributes(fieldDictionary, valueDictionary);
        event.getEncodedAttributes(fieldDictionary, valueDictionary);

        // Then - cache should still contain only 2 entries (no duplicates)
        Map<String, String> cache = getNormalizedStringsCache(event);
        assertThat(cache).hasSize(2);
        assertThat(cache).containsEntry("active", "ACTIVE");
        assertThat(cache).containsEntry("platinum", "PLATINUM");
    }

    @Test
    @DisplayName("Should handle mixed case strings correctly")
    void shouldHandleMixedCaseStringsCorrectly() {
        // Given
        Event event = new Event("evt-004", "TEST", Map.of(
                "status", "AcTiVe",
                "customer_tier", "PlAtInUm"
        ));

        // When
        Int2ObjectMap<Object> encoded = event.getEncodedAttributes(fieldDictionary, valueDictionary);

        // Then - should normalize to uppercase
        int statusFieldId = fieldDictionary.getId("STATUS");
        int tierFieldId = fieldDictionary.getId("CUSTOMER_TIER");

        int activeValueId = valueDictionary.getId("ACTIVE");
        int platinumValueId = valueDictionary.getId("PLATINUM");

        assertThat(encoded.get(statusFieldId)).isEqualTo(activeValueId);
        assertThat(encoded.get(tierFieldId)).isEqualTo(platinumValueId);
    }

    @Test
    @DisplayName("Should not normalize numeric values")
    void shouldNotNormalizeNumericValues() {
        // Given
        Event event = new Event("evt-005", "TEST", Map.of(
                "amount", 5000,
                "status", "active"
        ));

        // When
        Int2ObjectMap<Object> encoded = event.getEncodedAttributes(fieldDictionary, valueDictionary);

        // Then - numeric value should remain unchanged
        int amountFieldId = fieldDictionary.getId("AMOUNT");
        assertThat(encoded.get(amountFieldId)).isEqualTo(5000);

        // String value should be normalized
        int statusFieldId = fieldDictionary.getId("STATUS");
        int activeValueId = valueDictionary.getId("ACTIVE");
        assertThat(encoded.get(statusFieldId)).isEqualTo(activeValueId);
    }

    @Test
    @DisplayName("Should preserve original values in flattened attributes")
    void shouldPreserveOriginalValuesInFlattenedAttributes() {
        // Given
        Event event = new Event("evt-006", "TEST", Map.of(
                "status", "active",
                "description", "Test Description"
        ));

        // When
        Map<String, Object> flattened = event.getFlattenedAttributes();

        // Then - original values should be preserved (not uppercased)
        assertThat(flattened.get("STATUS")).isEqualTo("active");
        assertThat(flattened.get("DESCRIPTION")).isEqualTo("Test Description");
    }

    @Test
    @DisplayName("Should handle empty string values")
    void shouldHandleEmptyStringValues() {
        // Given
        Event event = new Event("evt-007", "TEST", Map.of(
                "status", "",
                "customer_tier", "platinum"
        ));

        // When
        Int2ObjectMap<Object> encoded = event.getEncodedAttributes(fieldDictionary, valueDictionary);

        // Then - should handle empty string without errors
        assertThat(encoded).isNotNull();
        int tierFieldId = fieldDictionary.getId("CUSTOMER_TIER");
        int platinumValueId = valueDictionary.getId("PLATINUM");
        assertThat(encoded.get(tierFieldId)).isEqualTo(platinumValueId);
    }

    @Test
    @DisplayName("Should handle nested attributes with string values")
    void shouldHandleNestedAttributesWithStringValues() {
        // Given
        Event event = new Event("evt-008", "TEST", Map.of(
                "user", Map.of(
                        "status", "active",
                        "tier", "platinum"
                )
        ));

        // When
        Int2ObjectMap<Object> encoded = event.getEncodedAttributes(fieldDictionary, valueDictionary);

        // Then - nested values should be normalized
        // Note: Nested keys are flattened with dot notation and normalized
        Dictionary nestedFieldDict = new Dictionary();
        nestedFieldDict.encode("USER.STATUS");
        nestedFieldDict.encode("USER.TIER");

        int userStatusFieldId = nestedFieldDict.getId("USER.STATUS");
        int userTierFieldId = nestedFieldDict.getId("USER.TIER");

        // Re-encode with nested field dictionary
        encoded = event.getEncodedAttributes(nestedFieldDict, valueDictionary);

        int activeValueId = valueDictionary.getId("ACTIVE");
        int platinumValueId = valueDictionary.getId("PLATINUM");

        assertThat(encoded.get(userStatusFieldId)).isEqualTo(activeValueId);
        assertThat(encoded.get(userTierFieldId)).isEqualTo(platinumValueId);
    }

    @Test
    @DisplayName("Should be thread-safe for concurrent access")
    void shouldBeThreadSafeForConcurrentAccess() throws Exception {
        // Given
        Event event = new Event("evt-009", "TEST", Map.of(
                "status", "active",
                "customer_tier", "platinum"
        ));

        // When - multiple threads access getEncodedAttributes concurrently
        int threadCount = 10;
        Thread[] threads = new Thread[threadCount];

        for (int i = 0; i < threadCount; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 100; j++) {
                    Int2ObjectMap<Object> encoded = event.getEncodedAttributes(fieldDictionary, valueDictionary);
                    assertThat(encoded).isNotNull();
                }
            });
            threads[i].start();
        }

        // Wait for all threads to complete
        for (Thread thread : threads) {
            thread.join();
        }

        // Then - cache should be consistent and contain expected values
        Map<String, String> cache = getNormalizedStringsCache(event);
        assertThat(cache).isNotNull();
        assertThat(cache).containsEntry("active", "ACTIVE");
        assertThat(cache).containsEntry("platinum", "PLATINUM");
    }

    @Test
    @DisplayName("Should handle null values gracefully")
    void shouldHandleNullValuesGracefully() {
        // Given
        Event event = new Event("evt-010", "TEST", Map.of(
                "status", "active"
                // Note: Map.of() doesn't allow null values, so we test with missing values
        ));

        // When
        Int2ObjectMap<Object> encoded = event.getEncodedAttributes(fieldDictionary, valueDictionary);

        // Then - should encode without errors
        assertThat(encoded).isNotNull();
        int statusFieldId = fieldDictionary.getId("STATUS");
        int activeValueId = valueDictionary.getId("ACTIVE");
        assertThat(encoded.get(statusFieldId)).isEqualTo(activeValueId);
    }

    @Test
    @DisplayName("Should maintain correctness for REGEX-compatible values")
    void shouldMaintainCorrectnessForRegexCompatibleValues() {
        // Given - event with values that might be used in REGEX predicates
        Event event = new Event("evt-011", "TEST", Map.of(
                "description", "Test Pattern 123"
        ));

        // When - get both flattened and encoded attributes
        Map<String, Object> flattened = event.getFlattenedAttributes();
        Int2ObjectMap<Object> encoded = event.getEncodedAttributes(fieldDictionary, valueDictionary);

        // Then - flattened should have original (for REGEX), encoded should normalize
        assertThat(flattened.get("DESCRIPTION")).isEqualTo("Test Pattern 123");

        // Encoded will uppercase for case-insensitive matching (non-REGEX operators)
        // REGEX operators will use the original value from flattened attributes
        assertThat(encoded).isNotNull();
    }

    /**
     * Helper method to access the private normalizedStringsCache field via reflection.
     * Used for testing cache behavior.
     */
    @SuppressWarnings("unchecked")
    private Map<String, String> getNormalizedStringsCache(Event event) throws Exception {
        Field cacheField = Event.class.getDeclaredField("normalizedStringsCache");
        cacheField.setAccessible(true);
        return (Map<String, String>) cacheField.get(event);
    }
}