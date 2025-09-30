package os.toolset.ruleengine.core.cache;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * High-performance in-memory implementation of BaseConditionCache.
 *
 * Features:
 * - Lock-free reads using ConcurrentHashMap
 * - LRU eviction with configurable max size
 * - TTL-based expiration
 * - Async operations (immediate completion for in-memory)
 * - Comprehensive metrics collection
 * - Thread-safe statistics using LongAdder
 *
 * Performance characteristics:
 * - Get: O(1) average, lock-free
 * - Put: O(1) average, minimal locking for eviction
 * - Memory: ~200 bytes per entry overhead
 *
 */
public class InMemoryBaseConditionCache implements BaseConditionCache {
    private static final Logger logger = Logger.getLogger(InMemoryBaseConditionCache.class.getName());

    // Cache storage with TTL tracking
    private final ConcurrentHashMap<String, InternalEntry> cache;

    // LRU tracking using a concurrent linked queue
    private final ConcurrentLinkedDeque<String> lruQueue;

    // Configuration
    private final int maxSize;
    private final long defaultTtlMillis;
    private final ScheduledExecutorService cleanupExecutor;

    // Metrics collection using lock-free counters
    private final LongAdder totalRequests = new LongAdder();
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final LongAdder evictions = new LongAdder();
    private final AtomicLong totalGetTimeNanos = new AtomicLong();
    private final AtomicLong getCount = new AtomicLong();
    private final AtomicLong totalPutTimeNanos = new AtomicLong();
    private final AtomicLong putCount = new AtomicLong();

    /**
     * Internal entry with metadata for TTL and statistics.
     */
    private static class InternalEntry {
        final BitSet result;
        final long createTimeNanos;
        final long ttlMillis;
        final LongAdder hitCount;

        InternalEntry(BitSet result, long ttlMillis) {
            this.result = (BitSet) result.clone(); // Defensive copy
            this.createTimeNanos = System.nanoTime();
            this.ttlMillis = ttlMillis;
            this.hitCount = new LongAdder();
        }

        boolean isExpired() {
            return (System.nanoTime() - createTimeNanos) > TimeUnit.MILLISECONDS.toNanos(ttlMillis);
        }

        CacheEntry toCacheEntry(String key) {
            return new CacheEntry(
                    (BitSet) result.clone(), // Return defensive copy
                    createTimeNanos,
                    hitCount.sum(),
                    key
            );
        }
    }

    /**
     * Create cache with specified configuration.
     *
     * @param maxSize Maximum number of entries (LRU eviction when exceeded)
     * @param defaultTtlMillis Default TTL in milliseconds
     */
    public InMemoryBaseConditionCache(int maxSize, long defaultTtlMillis) {
        this.maxSize = maxSize;
        this.defaultTtlMillis = defaultTtlMillis;
        this.cache = new ConcurrentHashMap<>(Math.min(maxSize, 10_000));
        this.lruQueue = new ConcurrentLinkedDeque<>();

        // Schedule periodic cleanup of expired entries
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "BaseConditionCache-Cleanup");
            t.setDaemon(true);
            return t;
        });

        // Run cleanup every minute
        cleanupExecutor.scheduleAtFixedRate(
                this::cleanupExpired,
                1, 1, TimeUnit.MINUTES
        );

        logger.info(String.format(
                "InMemoryBaseConditionCache initialized: maxSize=%d, defaultTTL=%dms",
                maxSize, defaultTtlMillis
        ));
    }

    /**
     * Builder for fluent configuration.
     */
    public static class Builder {
        private int maxSize = 10_000;
        private long defaultTtlMillis = TimeUnit.HOURS.toMillis(1);

        public Builder maxSize(int maxSize) {
            this.maxSize = maxSize;
            return this;
        }

        public Builder defaultTtl(long duration, TimeUnit unit) {
            this.defaultTtlMillis = unit.toMillis(duration);
            return this;
        }

        public InMemoryBaseConditionCache build() {
            return new InMemoryBaseConditionCache(maxSize, defaultTtlMillis);
        }
    }

    @Override
    public CompletableFuture<Optional<CacheEntry>> get(String cacheKey) {
        long startTime = System.nanoTime();
        totalRequests.increment();

        try {
            InternalEntry entry = cache.get(cacheKey);

            if (entry == null) {
                misses.increment();
                return CompletableFuture.completedFuture(Optional.empty());
            }

            // Check expiration
            if (entry.isExpired()) {
                cache.remove(cacheKey);
                lruQueue.remove(cacheKey);
                misses.increment();
                evictions.increment();
                return CompletableFuture.completedFuture(Optional.empty());
            }

            // Update LRU position
            updateLruPosition(cacheKey);

            // Update statistics
            entry.hitCount.increment();
            hits.increment();

            return CompletableFuture.completedFuture(
                    Optional.of(entry.toCacheEntry(cacheKey))
            );

        } finally {
            long duration = System.nanoTime() - startTime;
            totalGetTimeNanos.addAndGet(duration);
            getCount.incrementAndGet();
        }
    }

    @Override
    public CompletableFuture<Void> put(String cacheKey, BitSet result, long ttl, TimeUnit timeUnit) {
        long startTime = System.nanoTime();

        try {
            long ttlMillis = timeUnit.toMillis(ttl);
            InternalEntry newEntry = new InternalEntry(result, ttlMillis);

            // Check if we need to evict
            if (cache.size() >= maxSize) {
                evictLru();
            }

            // Store the entry
            InternalEntry oldEntry = cache.put(cacheKey, newEntry);

            // Update LRU queue
            if (oldEntry != null) {
                lruQueue.remove(cacheKey);
            }
            lruQueue.addFirst(cacheKey);

            if (logger.isLoggable(Level.FINER)) {
                logger.finer(String.format(
                        "Cached base condition: key=%s, ttl=%dms, resultSize=%d",
                        cacheKey, ttlMillis, result.cardinality()
                ));
            }

            return CompletableFuture.completedFuture(null);

        } finally {
            long duration = System.nanoTime() - startTime;
            totalPutTimeNanos.addAndGet(duration);
            putCount.incrementAndGet();
        }
    }

    @Override
    public CompletableFuture<Map<String, CacheEntry>> getBatch(Iterable<String> cacheKeys) {
        Map<String, CacheEntry> results = new ConcurrentHashMap<>();
        List<CompletableFuture<?>> futures = new ArrayList<>();

        for (String key : cacheKeys) {
            futures.add(
                    get(key).thenAccept(opt ->
                            opt.ifPresent(entry -> results.put(key, entry))
                    )
            );
        }

        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .thenApply(v -> results);
    }

    @Override
    public CompletableFuture<Void> invalidate(String cacheKey) {
        InternalEntry removed = cache.remove(cacheKey);
        if (removed != null) {
            lruQueue.remove(cacheKey);
            evictions.increment();
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> clear() {
        int size = cache.size();
        cache.clear();
        lruQueue.clear();
        evictions.add(size);
        logger.info("Cache cleared: " + size + " entries removed");
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CacheMetrics getMetrics() {
        long totalReq = totalRequests.sum();
        long hitCount = hits.sum();
        long missCount = misses.sum();
        long evictionCount = evictions.sum();

        long avgGetNanos = getCount.get() > 0 ?
                totalGetTimeNanos.get() / getCount.get() : 0;
        long avgPutNanos = putCount.get() > 0 ?
                totalPutTimeNanos.get() / putCount.get() : 0;

        double hitRate = totalReq > 0 ? (double) hitCount / totalReq : 0.0;

        return new CacheMetrics(
                totalReq,
                hitCount,
                missCount,
                evictionCount,
                cache.size(),
                hitRate,
                avgGetNanos,
                avgPutNanos
        );
    }

    /**
     * Update LRU position for accessed key.
     * This is optimized to minimize contention.
     */
    private void updateLruPosition(String cacheKey) {
        // Remove and re-add to move to front (most recently used)
        // This is a concurrent operation, so we use removeFirstOccurrence
        if (lruQueue.removeFirstOccurrence(cacheKey)) {
            lruQueue.addFirst(cacheKey);
        }
    }

    /**
     * Evict least recently used entry.
     */
    private void evictLru() {
        String evictKey = lruQueue.pollLast();
        if (evictKey != null) {
            cache.remove(evictKey);
            evictions.increment();

            if (logger.isLoggable(Level.FINE)) {
                logger.fine("Evicted LRU entry: " + evictKey);
            }
        }
    }

    /**
     * Periodic cleanup of expired entries.
     */
    private void cleanupExpired() {
        try {
            int removed = 0;
            Iterator<Map.Entry<String, InternalEntry>> it = cache.entrySet().iterator();

            while (it.hasNext()) {
                Map.Entry<String, InternalEntry> entry = it.next();
                if (entry.getValue().isExpired()) {
                    it.remove();
                    lruQueue.remove(entry.getKey());
                    removed++;
                    evictions.increment();
                }
            }

            if (removed > 0 && logger.isLoggable(Level.FINE)) {
                logger.fine("Cleanup removed " + removed + " expired entries");
            }

        } catch (Exception e) {
            logger.log(Level.WARNING, "Error during cache cleanup", e);
        }
    }

    /**
     * Shutdown cleanup executor on close.
     */
    public void shutdown() {
        cleanupExecutor.shutdown();
        try {
            if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                cleanupExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanupExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        logger.info("InMemoryBaseConditionCache shutdown complete");
    }
}