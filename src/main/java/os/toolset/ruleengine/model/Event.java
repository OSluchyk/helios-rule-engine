package os.toolset.ruleengine.model;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import os.toolset.ruleengine.core.Dictionary;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Represents an event to be evaluated, with support for dictionary encoding.
 */
public final class Event {
    private final String eventId;
    private final String eventType;
    private final Map<String, Object> attributes;
    private final long timestamp;

    private transient Map<String, Object> flattenedAttributesCache;
    private transient Int2ObjectMap<Object> encodedAttributesCache;

    public Event(String eventId, String eventType, Map<String, Object> attributes) {
        this.eventId = Objects.requireNonNull(eventId);
        this.eventType = eventType;
        this.attributes = attributes != null ? new HashMap<>(attributes) : Collections.emptyMap();
        this.timestamp = System.currentTimeMillis();
    }

    public String getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public Map<String, Object> getAttributes() { return Collections.unmodifiableMap(attributes); }
    public long getTimestamp() { return timestamp; }

    public Map<String, Object> getFlattenedAttributes() {
        if (flattenedAttributesCache == null) {
            flattenedAttributesCache = flattenMap(attributes);
        }
        return flattenedAttributesCache;
    }

    /**
     * Returns a map of encoded field IDs to their values.
     * This is the primary representation used by the RuleEvaluator.
     *
     * @param fieldDictionary The dictionary for encoding field names.
     * @param valueDictionary The dictionary for encoding string values.
     * @return A map where keys are integer field IDs.
     */
    public Int2ObjectMap<Object> getEncodedAttributes(Dictionary fieldDictionary, Dictionary valueDictionary) {
        if (encodedAttributesCache == null) {
            Map<String, Object> flattened = getFlattenedAttributes();
            encodedAttributesCache = new Int2ObjectOpenHashMap<>(flattened.size());
            for (Map.Entry<String, Object> entry : flattened.entrySet()) {
                int fieldId = fieldDictionary.getId(entry.getKey());
                if (fieldId != -1) {
                    Object value = entry.getValue();
                    if (value instanceof String) {
                        int valueId = valueDictionary.getId((String) value);
                        // If the value is not in the dictionary, use the raw string for operators like CONTAINS/REGEX
                        encodedAttributesCache.put(fieldId, valueId != -1 ? (Object) valueId : value);
                    } else {
                        encodedAttributesCache.put(fieldId, value);
                    }
                }
            }
        }
        return encodedAttributesCache;
    }

    private Map<String, Object> flattenMap(Map<String, Object> map) {
        Map<String, Object> result = new HashMap<>();
        if (map == null || map.isEmpty()) {
            return Collections.unmodifiableMap(result);
        }
        StringBuilder prefixBuilder = new StringBuilder();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            flattenEntry(prefixBuilder, entry.getKey(), entry.getValue(), result);
        }
        return Collections.unmodifiableMap(result);
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