package com.helios.ruleengine.model;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import com.helios.ruleengine.core.Dictionary;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Represents an event to be evaluated, with support for dictionary encoding.
 * P0-3 FIX: Eliminated unnecessary Collections.unmodifiableMap wrapper allocations
 */
public final class Event {
    private final String eventId;
    private final String eventType;
    private final Map<String, Object> attributes;
    private final long timestamp;

    // P0-3 FIX: Cache flattened attributes without unmodifiable wrapper (internal use only)
    private final Map<String, Object> flattenedAttributesCache;
    private volatile Int2ObjectMap<Object> encodedAttributesCache;

    public Event(String eventId, String eventType, Map<String, Object> attributes) {
        this.eventId = Objects.requireNonNull(eventId);
        this.eventType = eventType;
        this.attributes = attributes != null ? new HashMap<>(attributes) : Collections.emptyMap();
        this.timestamp = System.currentTimeMillis();
        // P0-3 FIX: Store directly without unmodifiable wrapper (we control access)
        this.flattenedAttributesCache = flattenMap(this.attributes);
    }

    public Event(String eventId, Map<String, Object> attributes) {
        this(eventId, "DEFAULT_EVENT_TYPE", attributes);
    }

    public String getEventId() { return eventId; }
    public String getEventType() { return eventType; }

    // P0-3 FIX: Only wrap when external access needed
    public Map<String, Object> getAttributes() {
        return Collections.unmodifiableMap(attributes);
    }

    public long getTimestamp() { return timestamp; }

    /**
     * P0-3 FIX: Return cached map directly (internal use, no defensive copy needed)
     */
    public Map<String, Object> getFlattenedAttributes() {
        return flattenedAttributesCache;
    }

    /**
     * Returns a map of encoded field IDs to their values. This method is now
     * thread-safe and ensures the encoding only happens once.
     */
    public Int2ObjectMap<Object> getEncodedAttributes(Dictionary fieldDictionary, Dictionary valueDictionary) {
        // Use a local variable to reduce volatile reads (double-checked locking pattern)
        Int2ObjectMap<Object> result = encodedAttributesCache;
        if (result == null) {
            synchronized (this) {
                result = encodedAttributesCache;
                if (result == null) {
                    Map<String, Object> flattened = getFlattenedAttributes();
                    final Int2ObjectOpenHashMap<Object> encoded = new Int2ObjectOpenHashMap<>(flattened.size());
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
                    encodedAttributesCache = result = encoded;
                }
            }
        }
        return result;
    }

    /**
     * P0-3 FIX: Returns plain HashMap, not wrapped in unmodifiableMap
     * (Only used internally, so defensive copy not needed)
     */
    private Map<String, Object> flattenMap(Map<String, Object> map) {
        Map<String, Object> result = new HashMap<>();
        if (map == null || map.isEmpty()) {
            return result;
        }
        StringBuilder prefixBuilder = new StringBuilder();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            flattenEntry(prefixBuilder, entry.getKey(), entry.getValue(), result);
        }
        return result;  // P0-3: No unmodifiableMap wrapper
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