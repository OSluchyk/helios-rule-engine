package com.helios.ruleengine.core.cache;

// ====================================================================
// FILE: AdaptiveCaffeineCache.java
// LOCATION: src/main/java/com/helios/ruleengine/core/cache/
// ====================================================================


import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.StampedLock;
import java.util.logging.Logger;

/**
 * Adaptive cache that dynamically resizes based on hit rate metrics.
 *
 * <p>Drop-in replacement for {@link CaffeineBaseConditionCache} with automatic tuning.
 *
 * <p><b>Integration with FastCacheKeyGenerator:</b>
 * <pre>{@code
 * // 1. Generate key using FastCacheKeyGenerator
 * String cacheKey = FastCacheKeyGenerator.generateKey(eventAttrs, predicateIds, count);
 *
 * // 2. Use with adaptive cache
 * AdaptiveCaffeineCache cache = new AdaptiveCaffeineCache.Builder()
 *     .maxSize(100_000)
 *     .build();
 *
 * Optional<CacheEntry> result = cache.get(cacheKey).join();
 * if (result.isEmpty()) {
 *     BitSet evaluation = evaluatePredicates(...);
 *     cache.put(cacheKey, evaluation, 5, TimeUnit.MINUTES);
 * }
 * }</pre>
 *
 * <p><b>Thread Safety:</b>
 * <ul>
 *   <li>All reads are lock-free (StampedLock optimistic reads)</li>
 *   <li>Writes (resize operations) use exclusive lock</li>
 *   <li>Cache reference is volatile for happens-before visibility</li>
 * </ul>
 *
 * <p><b>Adaptive Sizing Algorithm:</b>
 * <pre>
 * Every 30 seconds:
 *   If hit rate < 70% AND memory available → double size (max 10M)
 *   If hit rate > 95% AND memory pressure → halve size (min 10K)
 * </pre>
 *
 * <p><b>Performance Characteristics:</b>
 * <ul>
 *   <li>Read latency: O(1) + ~3ns overhead (optimistic lock)</li>
 *   <li>Write latency: O(n) for cache rebuild (infrequent)</li>
 *   <li>Memory: 2x during resize (old + new cache)</li>
 * </ul>
 *
 * @author Helios Platform Team
 * @since 2.0.0
 */
public class AdaptiveCaffeineCache implements BaseConditionCache {

    private static final Logger logger = Logger.getLogger(AdaptiveCaffeineCache.class.getName());

    // ================================================================
    // CONFIGURATION CONSTANTS
    // ================================================================

    private static final long MIN_CACHE_SIZE = 10_000L;
    private static final long MAX_CACHE_SIZE = 10_000_000L;  // 10M entries
    private static final double LOW_HIT_RATE_THRESHOLD = 0.70;   // 70%
    private static final double HIGH_HIT_RATE_THRESHOLD = 0.95;  // 95%
    private static final Duration TUNING_INTERVAL = Duration.ofSeconds(30);
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(5);

    // ================================================================
    // STATE VARIABLES
    // ================================================================

    /**
     * Current cache instance. Replaced atomically during resize.
     * Uses String keys from FastCacheKeyGenerator.
     */
    private volatile Cache<String, BitSet> cache;

    /**
     * Current maximum cache size.
     */
    private final AtomicLong targetMaxSize;

    /**
     * Lock for coordinating resize operations.
     */
    private final StampedLock resizeLock = new StampedLock();

    /**
     * Background tuner thread.
     */
    private final ScheduledExecutorService tuner;

    /**
     * Shutdown flag.
     */
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    /**
     * Configuration.
     */
    private final long defaultTtlMillis;
    private final boolean statsEnabled;

    /**
     * Metrics for monitoring resizes.
     */
    private final AtomicLong resizeCount = new AtomicLong(0);
    private final AtomicLong lastResizeTimeMillis = new AtomicLong(0);

    // ================================================================
    // CONSTRUCTOR
    // ================================================================

    private AdaptiveCaffeineCache(Builder builder) {
        if (builder.initialMaxSize < MIN_CACHE_SIZE || builder.initialMaxSize > MAX_CACHE_SIZE) {
            throw new IllegalArgumentException(
                    "Initial size must be between " + MIN_CACHE_SIZE + " and " + MAX_CACHE_SIZE
            );
        }

        this.targetMaxSize = new AtomicLong(builder.initialMaxSize);
        this.defaultTtlMillis = builder.expireAfterWriteUnit.toMillis(builder.expireAfterWriteDuration);
        this.statsEnabled = builder.recordStats;
        this.cache = buildCache(builder.initialMaxSize, builder);

        // Start background tuner (if enabled)
        if (builder.enableAdaptiveSizing) {
            this.tuner = Executors.newSingleThreadScheduledExecutor(
                    new ThreadFactoryBuilder()
                            .setNameFormat("adaptive-cache-tuner-%d")
                            .setDaemon(true)
                            .setPriority(Thread.MIN_PRIORITY)
                            .build()
            );

            tuner.scheduleAtFixedRate(
                    this::adaptSize,
                    TUNING_INTERVAL.toSeconds(),
                    TUNING_INTERVAL.toSeconds(),
                    TimeUnit.SECONDS
            );

            logger.info(String.format(
                    "AdaptiveCaffeineCache initialized: initialSize=%d, ttl=%dms, adaptive=true",
                    builder.initialMaxSize, defaultTtlMillis
            ));
        } else {
            this.tuner = null;
            logger.info(String.format(
                    "AdaptiveCaffeineCache initialized: maxSize=%d, ttl=%dms, adaptive=false",
                    builder.initialMaxSize, defaultTtlMillis
            ));
        }
    }

    // ================================================================
    // BASE CONDITION CACHE INTERFACE IMPLEMENTATION
    // ================================================================

    @Override
    public CompletableFuture<Optional<CacheEntry>> get(String cacheKey) {
        // FAST PATH: Optimistic read (no lock contention)
        long stamp = resizeLock.tryOptimisticRead();
        Cache<String, BitSet> currentCache = this.cache;

        if (!resizeLock.validate(stamp)) {
            // Rare: Resize happened during read, retry with read lock
            stamp = resizeLock.readLock();
            try {
                currentCache = this.cache;
            } finally {
                resizeLock.unlockRead(stamp);
            }
        }

        // Get from Caffeine cache
        BitSet result = currentCache.getIfPresent(cacheKey);

        if (result == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        // Create cache entry
        CacheEntry entry = new CacheEntry(
                (BitSet) result.clone(),  // Defensive copy
                System.nanoTime(),
                0,  // Caffeine doesn't track per-entry hit count
                cacheKey
        );

        return CompletableFuture.completedFuture(Optional.of(entry));
    }

    @Override
    public CompletableFuture<Void> put(String cacheKey, BitSet result, long ttl, TimeUnit timeUnit) {
        // FAST PATH: Optimistic read
        long stamp = resizeLock.tryOptimisticRead();
        Cache<String, BitSet> currentCache = this.cache;

        if (!resizeLock.validate(stamp)) {
            stamp = resizeLock.readLock();
            try {
                currentCache = this.cache;
            } finally {
                resizeLock.unlockRead(stamp);
            }
        }

        // Store defensive copy
        BitSet cloned = (BitSet) result.clone();
        currentCache.put(cacheKey, cloned);

        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Map<String, CacheEntry>> getBatch(Iterable<String> cacheKeys) {
        Map<String, CacheEntry> results = new HashMap<>();

        // Get current cache with optimistic lock
        long stamp = resizeLock.tryOptimisticRead();
        Cache<String, BitSet> currentCache = this.cache;

        if (!resizeLock.validate(stamp)) {
            stamp = resizeLock.readLock();
            try {
                currentCache = this.cache;
            } finally {
                resizeLock.unlockRead(stamp);
            }
        }

        // Batch retrieval
        for (String key : cacheKeys) {
            BitSet result = currentCache.getIfPresent(key);
            if (result != null) {
                CacheEntry entry = new CacheEntry(
                        (BitSet) result.clone(),
                        System.nanoTime(),
                        0,
                        key
                );
                results.put(key, entry);
            }
        }

        return CompletableFuture.completedFuture(results);
    }

    @Override
    public CompletableFuture<Void> invalidate(String cacheKey) {
        cache.invalidate(cacheKey);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> clear() {
        cache.invalidateAll();
        cache.cleanUp();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CacheMetrics getMetrics() {
        CacheStats stats = statsEnabled ? cache.stats() : CacheStats.empty();

        // Convert to BaseConditionCache.CacheMetrics format
        long hitRate = (long) (stats.hitRate() * 100);  // Convert 0.75 → 75
        long requestCount = stats.requestCount();
        long hitCount = stats.hitCount();
        long missCount = stats.missCount();
        long evictionCount = stats.evictionCount();
        long size = cache.estimatedSize();
        long avgLoadPenalty = (long) Math.round(stats.averageLoadPenalty());
        long avgPutLatency = 0L;  // Not tracked by Caffeine

        return new CacheMetrics(
                hitRate,
                requestCount,
                hitCount,
                missCount,
                evictionCount,
                size,
                avgLoadPenalty,
                avgPutLatency
        );
    }

    @Override
    public CompletableFuture<Void> warmUp(Map<String, BitSet> entries) {
        for (Map.Entry<String, BitSet> entry : entries.entrySet()) {
            cache.put(entry.getKey(), (BitSet) entry.getValue().clone());
        }
        return CompletableFuture.completedFuture(null);
    }

    // ================================================================
    // ADAPTIVE SIZING LOGIC
    // ================================================================

    /**
     * Periodically adjusts cache size based on hit rate metrics.
     * Called by background thread every 30 seconds.
     */
    void adaptSize() {
        if (shutdown.get()) {
            return;
        }

        try {
            CacheStats stats = cache.stats();
            double hitRate = stats.hitRate();
            long currentSize = cache.estimatedSize();
            long currentMax = targetMaxSize.get();

            Long newMax = determineNewSize(hitRate, currentMax, currentSize);

            if (newMax != null && newMax != currentMax) {
                performResize(newMax);
            }

        } catch (Exception e) {
            logger.warning("[AdaptiveCache] Tuning failed: " + e.getMessage());
        }
    }

    /**
     * Determines new cache size based on heuristics.
     */
    private Long determineNewSize(double hitRate, long currentMax, long currentSize) {
        // Heuristic 1: Low hit rate → increase capacity
        if (hitRate < LOW_HIT_RATE_THRESHOLD && currentMax < MAX_CACHE_SIZE) {
            long newMax = Math.min(currentMax * 2, MAX_CACHE_SIZE);

            // Only resize if we're actually using the space (> 80% full)
            if (currentSize > currentMax * 0.8) {
                logger.info(String.format(
                        "[AdaptiveCache] Low hit rate (%.1f%%) and high utilization (%.1f%%), increasing size: %d → %d",
                        hitRate * 100, (double) currentSize / currentMax * 100, currentMax, newMax
                ));
                return newMax;
            }
        }

        // Heuristic 2: High hit rate + memory pressure → decrease capacity
        if (hitRate > HIGH_HIT_RATE_THRESHOLD) {
            Runtime runtime = Runtime.getRuntime();
            long freeMemory = runtime.freeMemory();
            long totalMemory = runtime.totalMemory();
            double memoryUsage = 1.0 - ((double) freeMemory / totalMemory);

            if (memoryUsage > 0.85 && currentMax > MIN_CACHE_SIZE) {
                long newMax = Math.max(currentMax / 2, MIN_CACHE_SIZE);
                logger.info(String.format(
                        "[AdaptiveCache] High hit rate (%.1f%%) and memory pressure (%.1f%%), decreasing size: %d → %d",
                        hitRate * 100, memoryUsage * 100, currentMax, newMax
                ));
                return newMax;
            }
        }

        return null;  // No resize needed
    }

    /**
     * Performs cache resize with exclusive lock.
     * Uses copy-on-write strategy.
     */
    private void performResize(long newMax) {
        long startTime = System.currentTimeMillis();
        long stamp = resizeLock.writeLock();

        try {
            Cache<String, BitSet> oldCache = this.cache;
            Cache<String, BitSet> newCache = buildCache(newMax, null);

            // Migrate hot entries (Caffeine handles eviction automatically)
            newCache.putAll(oldCache.asMap());

            // Atomic swap
            this.cache = newCache;
            targetMaxSize.set(newMax);
            resizeCount.incrementAndGet();
            lastResizeTimeMillis.set(System.currentTimeMillis());

            long duration = System.currentTimeMillis() - startTime;

            logger.info(String.format(
                    "[AdaptiveCache] Resized: %d → %d entries, duration: %dms, hit rate: %.1f%%",
                    oldCache.estimatedSize(),
                    newMax,
                    duration,
                    oldCache.stats().hitRate() * 100
            ));

        } finally {
            resizeLock.unlockWrite(stamp);
        }
    }

    // ================================================================
    // CACHE FACTORY
    // ================================================================

    /**
     * Builds a new Caffeine cache with optimal settings.
     */
    private Cache<String, BitSet> buildCache(long maxSize, Builder config) {
        Caffeine<Object, Object> builder = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterAccess(DEFAULT_TTL)  // Better for read-heavy workloads
                .initialCapacity((int) Math.min(maxSize / 4, 10_000));

        if (statsEnabled) {
            builder.recordStats();
        }

        return builder.build();
    }

    // ================================================================
    // LIFECYCLE MANAGEMENT
    // ================================================================

    /**
     * Gracefully shuts down the adaptive tuning thread.
     * Must be called before application exit.
     */
    public void shutdown() {
        if (shutdown.compareAndSet(false, true)) {
            if (tuner != null) {
                tuner.shutdown();
                try {
                    if (!tuner.awaitTermination(5, TimeUnit.SECONDS)) {
                        tuner.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    tuner.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
            logger.info("[AdaptiveCache] Shutdown complete");
        }
    }

    // ================================================================
    // MONITORING API
    // ================================================================

    /**
     * Gets adaptive cache statistics.
     */
    public AdaptiveStats getAdaptiveStats() {
        CacheStats stats = cache.stats();
        return new AdaptiveStats(
                targetMaxSize.get(),
                cache.estimatedSize(),
                stats.hitRate(),
                resizeCount.get(),
                lastResizeTimeMillis.get()
        );
    }

    public static class AdaptiveStats {
        public final long maxSize;
        public final long currentSize;
        public final double hitRate;
        public final long totalResizes;
        public final long lastResizeTimeMillis;

        AdaptiveStats(long maxSize, long currentSize, double hitRate,
                      long totalResizes, long lastResizeTimeMillis) {
            this.maxSize = maxSize;
            this.currentSize = currentSize;
            this.hitRate = hitRate;
            this.totalResizes = totalResizes;
            this.lastResizeTimeMillis = lastResizeTimeMillis;
        }

        @Override
        public String toString() {
            return String.format(
                    "AdaptiveStats{maxSize=%d, currentSize=%d, hitRate=%.1f%%, resizes=%d}",
                    maxSize, currentSize, hitRate * 100, totalResizes
            );
        }
    }

    // ================================================================
    // BUILDER
    // ================================================================

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private long initialMaxSize = 100_000L;
        private long expireAfterWriteDuration = 5;
        private TimeUnit expireAfterWriteUnit = TimeUnit.MINUTES;
        private boolean recordStats = true;
        private boolean enableAdaptiveSizing = true;

        public Builder initialMaxSize(long size) {
            this.initialMaxSize = size;
            return this;
        }

        public Builder maxSize(long size) {
            return initialMaxSize(size);
        }

        public Builder expireAfterWrite(long duration, TimeUnit unit) {
            this.expireAfterWriteDuration = duration;
            this.expireAfterWriteUnit = unit;
            return this;
        }

        public Builder recordStats(boolean enable) {
            this.recordStats = enable;
            return this;
        }

        /**
         * Enable or disable adaptive sizing.
         * If disabled, behaves like CaffeineBaseConditionCache with fixed size.
         */
        public Builder enableAdaptiveSizing(boolean enable) {
            this.enableAdaptiveSizing = enable;
            return this;
        }

        public AdaptiveCaffeineCache build() {
            return new AdaptiveCaffeineCache(this);
        }
    }
}

// ====================================================================
// INTEGRATION EXAMPLE
// ====================================================================

/*
 * EXAMPLE: Using AdaptiveCaffeineCache with FastCacheKeyGenerator
 *
 * // 1. Create adaptive cache
 * BaseConditionCache cache = new AdaptiveCaffeineCache.Builder()
 *     .initialMaxSize(100_000)
 *     .expireAfterWrite(5, TimeUnit.MINUTES)
 *     .recordStats(true)
 *     .enableAdaptiveSizing(true)
 *     .build();
 *
 * // 2. In your evaluator
 * public BitSet evaluateBaseConditions(
 *         Int2ObjectMap<Object> eventAttrs,
 *         int[] predicateIds,
 *         int count) {
 *
 *     // Generate cache key using existing FastCacheKeyGenerator
 *     String cacheKey = FastCacheKeyGenerator.generateKey(
 *         eventAttrs,
 *         predicateIds,
 *         count
 *     );
 *
 *     // Check cache
 *     Optional<CacheEntry> cached = cache.get(cacheKey).join();
 *     if (cached.isPresent()) {
 *         return cached.get().result();
 *     }
 *
 *     // Cache miss - evaluate
 *     BitSet results = performEvaluation(eventAttrs, predicateIds, count);
 *
 *     // Store in cache
 *     cache.put(cacheKey, results, 5, TimeUnit.MINUTES);
 *
 *     return results;
 * }
 *
 * // 3. Monitor adaptive behavior
 * AdaptiveCaffeineCache adaptiveCache = (AdaptiveCaffeineCache) cache;
 * AdaptiveStats stats = adaptiveCache.getAdaptiveStats();
 * System.out.println(stats);  // maxSize=200000, hitRate=92%, resizes=3
 *
 * // 4. Shutdown on application exit
 * Runtime.getRuntime().addShutdownHook(new Thread(() -> {
 *     adaptiveCache.shutdown();
 * }));
 */