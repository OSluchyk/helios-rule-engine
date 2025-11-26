package com.helios.ruleengine.api.model;

import java.util.Map;

public record Event(
        String eventId,
        String eventType,
        Map<String, Object> attributes
) {}