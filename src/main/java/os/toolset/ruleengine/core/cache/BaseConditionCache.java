package os.toolset.ruleengine.core.cache;

import java.util.BitSet;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Abstraction for base condition caching to support both in-memory and distributed implementations.
 * This interface enables evaluation result caching for static predicate sets, reducing redundant
 * predicate evaluations by 90%+ for typical workloads.
 *
 * Design principles:
 * - Async-first API for non-blocking operations with distributed caches
 * - Support for TTL-based expiration
 * - Metrics collection built-in
 * - Batch operations for efficiency
 *
 */
public interface BaseConditionCache {

    /**
     * Represents a cached evaluation result with metadata.
     */
    record CacheEntry(
            BitSet result,
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
     * Store evaluation result in cache with TTL.
     *
     * @param cacheKey Unique key for this evaluation
     * @param result BitSet of evaluation results (true/false per rule)
     * @param ttl Time-to-live value
     * @param timeUnit Unit for TTL
     * @return Future that completes when the value is stored
     */
    CompletableFuture<Void> put(String cacheKey, BitSet result, long ttl, TimeUnit timeUnit);

    /**
     * Batch get operation for multiple cache keys.
     * Implementations should optimize this for bulk operations.
     *
     * @param cacheKeys Collection of cache keys to retrieve
     * @return Map of cache keys to their entries (missing keys won't be in map)
     */
    CompletableFuture<Map<String, CacheEntry>> getBatch(Iterable<String> cacheKeys);

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
     * Warm up cache with pre-computed entries (useful for startup).
     *
     * @param entries Map of cache keys to BitSet results
     * @return Future that completes when warmup is done
     */
    default CompletableFuture<Void> warmup(Map<String, BitSet> entries) {
        CompletableFuture<?>[] futures = entries.entrySet().stream()
                .map(e -> put(e.getKey(), e.getValue(), 1, TimeUnit.HOURS))
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(futures);
    }

    /**
     * Cache metrics for observability.
     */
    record CacheMetrics(
            long totalRequests,
            long hits,
            long misses,
            long evictions,
            long size,
            double hitRate,
            long avgGetTimeNanos,
            long avgPutTimeNanos
    ) {
        public static CacheMetrics empty() {
            return new CacheMetrics(0, 0, 0, 0, 0, 0.0, 0, 0);
        }

        public double getHitRate() {
            return totalRequests > 0 ? (double) hits / totalRequests : 0.0;
        }
    }
}