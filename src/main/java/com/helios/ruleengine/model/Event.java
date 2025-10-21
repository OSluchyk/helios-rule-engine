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
 * CACHING STRATEGY:
 * - Flattened attributes: Cached (safe - no external dependencies)
 * - Encoded attributes: NOT cached (prevents stale dictionary ID issues across different models)
 *
 * String values are uppercased for case-insensitive matching.
 */
public final class Event {
    private static final Map<String, Object> EMPTY_MAP = Collections.emptyMap();

    private final String eventId;
    private final String eventType;
    private final Map<String, Object> attributes;

    // Cache flattened attributes (safe - derived only from event's own data)
    private volatile Map<String, Object> flattenedAttributesCache;

    public Event(String eventId, String eventType, Map<String, Object> attributes) {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("Event ID cannot be null or blank");
        }
        this.eventId = eventId;
        this.eventType = eventType;
        this.attributes = (attributes == null) ? EMPTY_MAP : new HashMap<>(attributes);
    }

    public String eventId() {
        return eventId;
    }

    public String eventType() {
        return eventType;
    }

    public Map<String, Object> attributes() {
        return attributes;
    }

    // Legacy getters for backward compatibility
    public String getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    /**
     * Returns a flattened view of nested attributes with normalized keys.
     * Keys are UPPER_SNAKE_CASE with nested keys joined by dots.
     *
     * Cached for performance since flattening is deterministic.
     */
    public Map<String, Object> getFlattenedAttributes() {
        // Double-checked locking pattern for thread-safe lazy initialization
        Map<String, Object> result = flattenedAttributesCache;
        if (result == null) {
            synchronized (this) {
                result = flattenedAttributesCache;
                if (result == null) {
                    flattenedAttributesCache = result = flattenMap(attributes);
                }
            }
        }
        return result;
    }

    /**
     * Encodes event attributes using provided dictionaries.
     *
     * NOT cached - always recomputes to ensure correct behavior
     * when the same Event is used with different EngineModel instances.
     * String values are uppercased for case-insensitive matching.
     */
    public Int2ObjectMap<Object> getEncodedAttributes(Dictionary fieldDictionary, Dictionary valueDictionary) {
        Map<String, Object> flattened = getFlattenedAttributes();
        Int2ObjectOpenHashMap<Object> encoded = new Int2ObjectOpenHashMap<>(flattened.size());

        for (Map.Entry<String, Object> entry : flattened.entrySet()) {
            int fieldId = fieldDictionary.getId(entry.getKey());
            if (fieldId != -1) {
                Object value = entry.getValue();
                if (value instanceof String) {
                    // Uppercase string value for case-insensitive matching
                    String upperValue = ((String) value).toUpperCase();
                    int valueId = valueDictionary.getId(upperValue);
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Event event = (Event) o;
        return Objects.equals(eventId, event.eventId) &&
                Objects.equals(eventType, event.eventType) &&
                Objects.equals(attributes, event.attributes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(eventId, eventType, attributes);
    }

    @Override
    public String toString() {
        return "Event[" +
                "eventId='" + eventId + '\'' +
                ", eventType='" + eventType + '\'' +
                ", attributes=" + attributes +
                ']';
    }
}