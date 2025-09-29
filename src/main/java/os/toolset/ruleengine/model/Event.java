package os.toolset.ruleengine.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Represents an event to be evaluated against rules.
 */
public final class Event {
    private final String eventId;
    private final String eventType;
    private final Map<String, Object> attributes;
    private final long timestamp;

    // Cache for flattened attributes to avoid re-computation
    private transient Map<String, Object> flattenedAttributesCache;

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

    /**
     * Returns a flattened and normalized view of the event attributes.
     * Keys are normalized to UPPER_SNAKE_CASE for consistent matching.
     * The result is cached for performance.
     */
    public Map<String, Object> getFlattenedAttributes() {
        if (flattenedAttributesCache == null) {
            flattenedAttributesCache = flattenMap(attributes);
        }
        return flattenedAttributesCache;
    }

    private Map<String, Object> flattenMap(Map<String, Object> map) {
        Map<String, Object> result = new HashMap<>();
        if (map == null || map.isEmpty()) {
            return Collections.unmodifiableMap(result);
        }
        // Use a StringBuilder for efficient key construction to minimize object allocation
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
        // Normalize the key part efficiently without creating intermediate strings
        for (char c : key.toCharArray()) {
            prefix.append(Character.toUpperCase(c == '-' ? '_' : c));
        }

        if (value instanceof Map) {
            ((Map<String, Object>) value).forEach((k, v) -> flattenEntry(prefix, k, v, flatMap));
        } else {
            flatMap.put(prefix.toString(), value);
        }

        // Backtrack the StringBuilder to its original state for the next sibling key
        prefix.setLength(originalLength);
    }
}
