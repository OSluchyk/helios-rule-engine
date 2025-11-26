/*
 * Copyright (c) 2025 Helios Rule Engine
 * Licensed under the Apache License, Version 2.0
 */
package com.helios.ruleengine.runtime.context;

import com.helios.ruleengine.api.model.Event;
import com.helios.ruleengine.runtime.model.Dictionary;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Encodes API Events into dictionary-indexed attributes for evaluation.
 *
 * <p>
 * This class handles the conversion from the clean API {@link Event} (with
 * String keys
 * and Object values) to the internal representation using dictionary-encoded
 * integer IDs.
 *
 * <h2>Performance Optimizations</h2>
 * <ul>
 * <li><b>ThreadLocal Pooling:</b> Reuses Int2ObjectMap instances to avoid
 * allocations</li>
 * <li><b>String Normalization Cache:</b> Caches uppercased strings to avoid
 * repeated toUpperCase() calls</li>
 * <li><b>Flattening Cache:</b> Caches flattened attribute maps per event</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>
 * This class is thread-safe. Each thread gets its own buffer via ThreadLocal,
 * and the string normalization cache uses ConcurrentHashMap.
 *
 * <h2>Usage</h2>
 * 
 * <pre>{@code
 * EventEncoder encoder = new EventEncoder(fieldDict, valueDict);
 * Int2ObjectMap<Object> encoded = encoder.encode(apiEvent);
 * }</pre>
 */
public final class EventEncoder {

    // ThreadLocal pool for encoded attributes map (reused across evaluations)
    private static final ThreadLocal<Int2ObjectOpenHashMap<Object>> ENCODED_BUFFER = ThreadLocal
            .withInitial(() -> new Int2ObjectOpenHashMap<>(32));

    // Global string normalization cache (shared across threads for maximum reuse)
    // ConcurrentHashMap provides thread-safe, lock-free access
    private static final ConcurrentMap<String, String> NORMALIZED_STRING_CACHE = new ConcurrentHashMap<>(1024);

    // ThreadLocal cache for the last flattened event to avoid re-flattening
    private static final ThreadLocal<LastFlattenedEvent> FLATTENED_BUFFER = ThreadLocal
            .withInitial(LastFlattenedEvent::new);

    private static class LastFlattenedEvent {
        Event event;
        Map<String, Object> flattened;
    }

    private final Dictionary fieldDictionary;
    private final Dictionary valueDictionary;

    /**
     * Creates an encoder with the specified dictionaries.
     *
     * @param fieldDictionary dictionary for field name encoding
     * @param valueDictionary dictionary for value encoding
     */
    public EventEncoder(Dictionary fieldDictionary, Dictionary valueDictionary) {
        this.fieldDictionary = fieldDictionary;
        this.valueDictionary = valueDictionary;
    }

    /**
     * Encodes an API Event's attributes into dictionary-indexed form.
     *
     * <p>
     * The returned map uses integer field IDs as keys (from fieldDictionary)
     * and either integer value IDs (for known string values) or raw values
     * (for numeric/boolean/unknown values).
     *
     * <p>
     * <b>Important:</b> The returned map is reused across calls on the same thread.
     * Do not hold references to it across evaluations.
     *
     * @param event the API event to encode
     * @return encoded attributes map (pooled, do not retain reference)
     */
    public Int2ObjectMap<Object> encode(Event event) {
        Map<String, Object> flattened = getFlattenedAttributes(event);

        // Reuse thread-local buffer
        Int2ObjectOpenHashMap<Object> encoded = ENCODED_BUFFER.get();
        encoded.clear();

        for (Map.Entry<String, Object> entry : flattened.entrySet()) {
            int fieldId = fieldDictionary.getId(entry.getKey());
            if (fieldId != -1) {
                Object value = entry.getValue();
                if (value instanceof String strValue) {
                    // Get normalized (uppercased) string from cache
                    String upperValue = getNormalizedString(strValue);
                    int valueId = valueDictionary.getId(upperValue);
                    encoded.put(fieldId, valueId != -1 ? (Object) valueId : value);
                } else {
                    encoded.put(fieldId, value);
                }
            }
        }

        return encoded;
    }

    /**
     * Gets the original (non-normalized) flattened attributes.
     * Used by REGEX operators that need case-sensitive matching.
     *
     * @param event the API event
     * @return flattened attributes with original values
     */
    public Map<String, Object> getFlattenedAttributes(Event event) {
        LastFlattenedEvent cache = FLATTENED_BUFFER.get();
        // Identity check is sufficient and fastest for this optimization
        if (cache.event == event) {
            return cache.flattened;
        }

        Map<String, Object> flattened = flattenAttributes(event.attributes());
        cache.event = event;
        cache.flattened = flattened;
        return flattened;
    }

    /**
     * Gets normalized (uppercased) string with caching.
     * This eliminates the 51% CPU overhead from repeated toUpperCase() calls.
     */
    private String getNormalizedString(String original) {
        return NORMALIZED_STRING_CACHE.computeIfAbsent(original, String::toUpperCase);
    }

    /**
     * Flattens nested attribute maps with UPPER_SNAKE_CASE key normalization.
     */
    private Map<String, Object> flattenAttributes(Map<String, Object> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return Map.of();
        }

        Map<String, Object> result = new HashMap<>();
        StringBuilder prefixBuilder = new StringBuilder();

        for (Map.Entry<String, Object> entry : attributes.entrySet()) {
            flattenEntry(prefixBuilder, entry.getKey(), entry.getValue(), result);
        }

        return result;
    }

    @SuppressWarnings("unchecked")
    private void flattenEntry(StringBuilder prefix, String key, Object value, Map<String, Object> flatMap) {
        int originalLength = prefix.length();

        if (originalLength > 0) {
            prefix.append('.');
        }

        // Normalize key to UPPER_SNAKE_CASE
        for (char c : key.toCharArray()) {
            prefix.append(Character.toUpperCase(c == '-' ? '_' : c));
        }

        if (value instanceof Map) {
            ((Map<String, Object>) value).forEach((k, v) -> flattenEntry(prefix, k, v, flatMap));
        } else {
            flatMap.put(prefix.toString(), value);
        }

        prefix.setLength(originalLength);
    }

    /**
     * Clears the string normalization cache.
     * Useful for testing or when dictionaries change.
     */
    public static void clearNormalizationCache() {
        NORMALIZED_STRING_CACHE.clear();
    }

    /**
     * Returns the current size of the normalization cache.
     */
    public static int getNormalizationCacheSize() {
        return NORMALIZED_STRING_CACHE.size();
    }
}