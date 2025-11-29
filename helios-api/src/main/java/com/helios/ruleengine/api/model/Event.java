package com.helios.ruleengine.api.model;

import java.util.Map;

/**
 * Represents an immutable business event to be evaluated by the rule engine.
 *
 * <p>
 * An event consists of:
 * <ul>
 * <li><b>eventId</b>: A unique identifier for traceability.</li>
 * <li><b>eventType</b>: A logical type (e.g., "ORDER", "LOGIN") used for
 * routing or filtering.</li>
 * <li><b>attributes</b>: A map of key-value pairs representing the event
 * payload.</li>
 * </ul>
 *
 * @param eventId    Unique identifier for the event (must not be null).
 * @param eventType  Logical type of the event (can be null if not used).
 * @param attributes Map of event attributes (keys are normalized to uppercase).
 */
public record Event(
                String eventId,
                String eventType,
                Map<String, Object> attributes) {
}