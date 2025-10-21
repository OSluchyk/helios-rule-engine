/*
 * Copyright (c) 2025 Helios Rule Engine
 * Licensed under the Apache License, Version 2.0
 */
package com.helios.ruleengine.model;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import com.helios.ruleengine.core.model.Dictionary;

import java.util.*;

/**
 * Represents an event to be evaluated against rules.
 *
 * FIX: Removed encoded attributes caching to prevent stale cache issues
 * when the same Event object is reused with different EngineModel instances
 * (which have different dictionaries).
 */
public record Event(
        String eventId,
        String eventType,
        Map<String, Object> attributes
) {
    private static final Map<String, Object> EMPTY_MAP = Collections.emptyMap();

    // FIX: Removed encodedAttributesCache and flattenedAttributesCache
    // to prevent cache invalidation issues across different models

    public Event {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("Event ID cannot be null or blank");
        }
        attributes = (attributes == null) ? EMPTY_MAP : new HashMap<>(attributes);
    }

    /**
     * Getter for eventId (for compatibility with code using getEventId()).
     */
    public String getEventId() {
        return eventId;
    }

    /**
     * Getter for eventType (for compatibility with code using getEventType()).
     */
    public String getEventType() {
        return eventType;
    }

    /**
     * Getter for attributes (for compatibility with code using getAttributes()).
     */
    public Map<String, Object> getAttributes() {
        return attributes;
    }

    /**
     * Returns a flattened view of nested attributes with normalized keys.
     * Keys are UPPER_SNAKE_CASE with nested keys joined by dots.
     *
     * FIX: No longer cached to prevent issues when used with different models.
     */
    public Map<String, Object> getFlattenedAttributes() {
        return flattenMap(attributes);
    }

    /**
     * Encodes event attributes using provided dictionaries.
     *
     * FIX: No longer cached. Each call recomputes encoding to ensure
     * correct behavior when used with different EngineModel instances.
     * The performance impact is minimal as this is called once per evaluation.
     */
    public Int2ObjectMap<Object> getEncodedAttributes(Dictionary fieldDictionary, Dictionary valueDictionary) {
        Map<String, Object> flattened = getFlattenedAttributes();
        Int2ObjectOpenHashMap<Object> encoded = new Int2ObjectOpenHashMap<>(flattened.size());

        for (Map.Entry<String, Object> entry : flattened.entrySet()) {
            int fieldId = fieldDictionary.getId(entry.getKey());
            if (fieldId != -1) {
                Object value = entry.getValue();
                if (value instanceof String) {
                    int valueId = valueDictionary.getId((String) value);
                    encoded.put(fieldId, valueId != -1 ? (Object) valueId : value);
                } else {
                    encoded.put(fieldId, value);
                }
            }
        }

        return encoded;
    }

    private Map<String, Object> flattenMap(Map<String, Object> map) {
        Map<String, Object> result = new HashMap<>();
        if (map == null || map.isEmpty()) {
            return result;
        }
        StringBuilder prefixBuilder = new StringBuilder();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
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
}