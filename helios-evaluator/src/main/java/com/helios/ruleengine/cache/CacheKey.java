package com.helios.ruleengine.cache;

import java.io.Serializable;

/**
 * Composite cache key to avoid String allocations.
 * Wraps 128-bit hash (two longs) for high collision resistance.
 */
public record CacheKey(long h1, long h2) implements Serializable {

    @Override
    public String toString() {
        // Fallback to hex string if needed (e.g. for Redis)
        return String.format("%016x%016x", h1, h2);
    }
}
