/*
 * Copyright (c) 2025 Helios Rule Engine
 * Licensed under the Apache License, Version 2.0
 */
package com.helios.ruleengine.infra.cache;

import org.roaringbitmap.RoaringBitmap;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * ✅ P0-A FIX: Updated to use RoaringBitmap instead of BitSet
 *
 * Abstraction for base condition caching to support both in-memory and distributed implementations.
 * This interface enables evaluation result caching for static predicate sets, reducing redundant
 * predicate evaluations by 90%+ for typical workloads.
 * <p>
 * Design principles:
 * - Async-first API for non-blocking operations with distributed caches
 * - Support for TTL-based expiration
 * - Metrics collection built-in
 * - Batch operations for efficiency
 * - ✅ RoaringBitmap for efficient storage and iteration
 *
 */
public interface BaseConditionCache {

    /**
     * ✅ P0-A FIX: Represents a cached evaluation result with RoaringBitmap
     *
     * CHANGED: result field from BitSet to RoaringBitmap
     */
    record CacheEntry(
            RoaringBitmap result,  // ✅ Changed from BitSet to RoaringBitmap
            long createTimeNanos,
            long hitCount,
            String cacheKey
    ) {
        public boolean isExpired(long ttlMillis) {
            return (System.nanoTime() - createTimeNanos) > TimeUnit.MILLISECONDS.toNanos(ttlMillis);
        }
    }

    /**
     * Get cached evaluation result for a base condition set.
     *
     * @param cacheKey Unique key identifying the base condition set + event attributes
     * @return Optional containing the cached result if present and valid
     */
    CompletableFuture<Optional<CacheEntry>> get(String cacheKey);

    /**
     * ✅ P0-A FIX: Store evaluation result in cache with TTL using RoaringBitmap
     *
     * CHANGED: result parameter from BitSet to RoaringBitmap
     *
     * @param cacheKey Unique key for this evaluation
     * @param result   RoaringBitmap of evaluation results (matched rule IDs)
     * @param ttl      Time-to-live value
     * @param timeUnit Unit for TTL
     * @return Future that completes when the value is stored
     */
    CompletableFuture<Void> put(String cacheKey, RoaringBitmap result, long ttl, TimeUnit timeUnit);

    /**
     * Batch get operation for multiple cache keys.
     * Implementations should optimize this for bulk operations.
     *
     * @param cacheKeys Collection of cache keys to retrieve
     * @return Map of cache keys to their entries (missing keys won't be in map)
     */
    CompletableFuture<Map<String, CacheEntry>> getBatch(Iterable<String> cacheKeys);

    /**
     * ✅ P0-A FIX: Warm up cache with pre-computed entries using RoaringBitmap
     *
     * CHANGED: entries parameter from Map<String, BitSet> to Map<String, RoaringBitmap>
     */
    default CompletableFuture<Void> warmUp(Map<String, RoaringBitmap> entries) {
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Invalidate specific cache entry.
     *
     * @param cacheKey Key to invalidate
     * @return Future that completes when invalidation is done
     */
    CompletableFuture<Void> invalidate(String cacheKey);

    /**
     * Clear entire cache. Use with caution in production.
     *
     * @return Future that completes when cache is cleared
     */
    CompletableFuture<Void> clear();

    /**
     * Get cache statistics for monitoring.
     *
     * @return Current cache metrics
     */
    CacheMetrics getMetrics();

    /**
     * Cache metrics for monitoring and tuning.
     */
    record CacheMetrics(
            long totalRequests,
            long hits,
            long misses,
            long evictions,
            long currentSize,
            double hitRate,
            long avgGetTimeNanos,
            long avgPutTimeNanos
    ) {
        public String format() {
            return String.format(
                    "Cache Metrics: requests=%d, hits=%d (%.1f%%), misses=%d, evictions=%d, size=%d",
                    totalRequests, hits, hitRate * 100, misses, evictions, currentSize
            );
        }
    }
}