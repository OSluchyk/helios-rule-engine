package com.helios.ruleengine.core.pool;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Generic object pool using thread-local storage for zero-contention pooling.
 *
 * This is a STANDALONE implementation that can be used for any object type.
 * It does NOT depend on MatchResult or any project-specific classes.
 *
 * USAGE EXAMPLE:
 * <pre>
 * // Create a pool with factory and reset function
 * ResultObjectPool<MyObject> pool = new ResultObjectPool<>(
 *     MyObject::new,                    // Factory: how to create new objects
 *     obj -> obj.reset()                // Reset: how to clean object state
 * );
 *
 * // Use in hot path
 * MyObject obj = pool.acquire();
 * try {
 *     // Use object
 * } finally {
 *     pool.release(obj);  // ALWAYS release in finally
 * }
 * </pre>
 *
 * @param <T> Type of object to pool
 */
public class ResultObjectPool<T> {
    private static final Logger logger = Logger.getLogger(ResultObjectPool.class.getName());

    // Thread-local pools for zero-contention access
    private final ThreadLocal<LocalPool<T>> localPool;

    // Global overflow pool
    private final BlockingQueue<T> overflowPool;

    // Object lifecycle hooks
    private final ObjectFactory<T> factory;
    private final ObjectResetter<T> resetter;

    // Metrics
    private final AtomicLong totalAcquires = new AtomicLong();
    private final AtomicLong totalReleases = new AtomicLong();
    private final AtomicLong overflowAcquires = new AtomicLong();
    private final AtomicLong allocations = new AtomicLong();

    private final int localPoolSize;
    private final int overflowPoolSize;

    /**
     * Create pool with custom factory and resetter
     */
    public ResultObjectPool(ObjectFactory<T> factory, ObjectResetter<T> resetter) {
        this(factory, resetter, 32, 1000);
    }

    /**
     * Create pool with custom sizing
     */
    public ResultObjectPool(ObjectFactory<T> factory, ObjectResetter<T> resetter,
                            int localPoolSize, int overflowPoolSize) {
        this.factory = factory;
        this.resetter = resetter;
        this.localPoolSize = localPoolSize;
        this.overflowPoolSize = overflowPoolSize;
        this.overflowPool = new ArrayBlockingQueue<>(overflowPoolSize);
        this.localPool = ThreadLocal.withInitial(() -> new LocalPool<>(localPoolSize));

        logger.info(String.format(
                "ResultObjectPool initialized: localSize=%d, overflowSize=%d",
                localPoolSize, overflowPoolSize
        ));
    }

    /**
     * Acquire object from pool
     */
    public T acquire() {
        totalAcquires.incrementAndGet();

        // Try thread-local pool first (zero contention)
        LocalPool<T> local = localPool.get();
        T obj = local.acquire();

        if (obj != null) {
            return obj;
        }

        // Try global overflow pool
        obj = overflowPool.poll();
        if (obj != null) {
            overflowAcquires.incrementAndGet();
            resetter.reset(obj);  // Clean state
            return obj;
        }

        // Allocate new object (only when pools exhausted)
        allocations.incrementAndGet();
        return factory.create();
    }

    /**
     * Release object back to pool
     *
     * CRITICAL: Always call in finally block
     */
    public void release(T obj) {
        if (obj == null) {
            return;
        }

        totalReleases.incrementAndGet();

        // Reset state before returning to pool
        resetter.reset(obj);

        // Try to return to thread-local pool
        LocalPool<T> local = localPool.get();
        if (local.release(obj)) {
            return;  // Success
        }

        // Thread-local pool full, try overflow pool
        overflowPool.offer(obj);
        // If overflow also full, object will be GC'd (acceptable)
    }

    /**
     * Get pool statistics
     */
    public PoolStats getStats() {
        long acquires = totalAcquires.get();
        long releases = totalReleases.get();
        long allocs = allocations.get();
        long overflow = overflowAcquires.get();

        double reuseRate = acquires > 0 ? 1.0 - ((double) allocs / acquires) : 0.0;

        return new PoolStats(
                acquires,
                releases,
                allocs,
                overflow,
                reuseRate,
                overflowPool.size()
        );
    }

    /**
     * Print diagnostic information
     */
    public void printStats() {
        PoolStats stats = getStats();
        System.out.println("\n" + "=".repeat(60));
        System.out.println("📊 OBJECT POOL STATISTICS");
        System.out.println("=".repeat(60));
        System.out.printf("Total Acquires:        %,d\n", stats.totalAcquires);
        System.out.printf("Total Releases:        %,d\n", stats.totalReleases);
        System.out.printf("New Allocations:       %,d\n", stats.allocations);
        System.out.printf("Overflow Acquires:     %,d\n", stats.overflowAcquires);
        System.out.printf("Reuse Rate:            %.2f%%\n", stats.reuseRate * 100);
        System.out.printf("Overflow Pool Size:    %d\n", stats.overflowPoolSize);
        System.out.printf("Outstanding Objects:   %d\n", stats.totalAcquires - stats.totalReleases);
        System.out.println("=".repeat(60));
    }

    // Functional interfaces for object lifecycle

    @FunctionalInterface
    public interface ObjectFactory<T> {
        T create();
    }

    @FunctionalInterface
    public interface ObjectResetter<T> {
        void reset(T obj);
    }

    // Thread-local pool implementation
    private static class LocalPool<T> {
        private final Object[] pool;  // Use Object[] for generic type
        private int size = 0;

        LocalPool(int maxSize) {
            this.pool = new Object[maxSize];
        }

        @SuppressWarnings("unchecked")
        T acquire() {
            if (size > 0) {
                T obj = (T) pool[--size];
                pool[size] = null;  // Clear reference
                return obj;
            }
            return null;
        }

        boolean release(T obj) {
            if (size < pool.length) {
                pool[size++] = obj;
                return true;
            }
            return false;
        }
    }

    // Statistics container
    public static class PoolStats {
        public final long totalAcquires;
        public final long totalReleases;
        public final long allocations;
        public final long overflowAcquires;
        public final double reuseRate;
        public final int overflowPoolSize;

        PoolStats(long totalAcquires, long totalReleases, long allocations,
                  long overflowAcquires, double reuseRate, int overflowPoolSize) {
            this.totalAcquires = totalAcquires;
            this.totalReleases = totalReleases;
            this.allocations = allocations;
            this.overflowAcquires = overflowAcquires;
            this.reuseRate = reuseRate;
            this.overflowPoolSize = overflowPoolSize;
        }
    }
}