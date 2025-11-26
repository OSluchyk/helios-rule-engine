package com.helios.ruleengine.api.model;

import java.io.Serializable;
import java.util.Map;

public record EngineStats(
        int uniqueCombinations,
        int totalPredicates,
        long compilationTimeNanos,
        Map<String, Object> metadata
) implements Serializable {
}
