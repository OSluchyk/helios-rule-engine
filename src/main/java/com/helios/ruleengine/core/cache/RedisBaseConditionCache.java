package com.helios.ruleengine.core.cache;

import org.redisson.Redisson;
import org.redisson.api.RMapCache;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Redis-based implementation of BaseConditionCache for distributed caching.
 *
 * This implementation provides:
 * - Distributed caching across multiple application instances
 * - Automatic failover with Redis Sentinel or Cluster
 * - TTL-based expiration handled by Redis
 * - Async operations for non-blocking I/O
 * - Compression for large BitSets
 *
 * Migration steps from InMemoryBaseConditionCache:
 * 1. Add Redis/Redisson dependencies to pom.xml
 * 2. Configure Redis connection (see builder)
 * 3. Replace cache instantiation in RuleEvaluator
 * 4. Deploy Redis infrastructure
 * 5. Monitor cache metrics during rollout
 *
 */
public class RedisBaseConditionCache implements BaseConditionCache {
    private static final Logger logger = Logger.getLogger(RedisBaseConditionCache.class.getName());
    private static final String CACHE_PREFIX = "rule_engine:base:";
    private static final String METRICS_KEY = "rule_engine:metrics";

    private final RedissonClient redisson;
    private final RMapCache<String, byte[]> cacheMap;
    private final int compressionThreshold;

    // Local metrics (could also be stored in Redis for aggregation)
    private final AtomicLong totalRequests = new AtomicLong();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong evictions = new AtomicLong();

    /**
     * Create Redis cache with specified configuration.
     */
    public RedisBaseConditionCache(RedissonClient redisson, int compressionThreshold) {
        this.redisson = redisson;
        this.cacheMap = redisson.getMapCache(CACHE_PREFIX + "map");
        this.compressionThreshold = compressionThreshold;

        logger.info("RedisBaseConditionCache initialized with compression threshold: " + compressionThreshold);
    }

    /**
     * Builder for Redis cache configuration.
     */
    public static class Builder {
        private String redisAddress = "redis://localhost:6379";
        private String password = null;
        private int connectionPoolSize = 64;
        private int connectionMinimumIdleSize = 24;
        private int timeout = 3000;
        private int compressionThreshold = 1024; // Compress BitSets larger than 1KB
        private boolean useCluster = false;

        public Builder redisAddress(String address) {
            this.redisAddress = address;
            return this;
        }

        public Builder password(String password) {
            this.password = password;
            return this;
        }

        public Builder connectionPoolSize(int size) {
            this.connectionPoolSize = size;
            return this;
        }

        public Builder compressionThreshold(int bytes) {
            this.compressionThreshold = bytes;
            return this;
        }

        public Builder useCluster(boolean cluster) {
            this.useCluster = cluster;
            return this;
        }

        public RedisBaseConditionCache build() {
            Config config = new Config();

            if (useCluster) {
                config.useClusterServers()
                        .addNodeAddress(redisAddress)
                        .setPassword(password)
                        .setMasterConnectionPoolSize(connectionPoolSize)
                        .setMasterConnectionMinimumIdleSize(connectionMinimumIdleSize)
                        .setTimeout(timeout);
            } else {
                config.useSingleServer()
                        .setAddress(redisAddress)
                        .setPassword(password)
                        .setConnectionPoolSize(connectionPoolSize)
                        .setConnectionMinimumIdleSize(connectionMinimumIdleSize)
                        .setTimeout(timeout);
            }

            RedissonClient redisson = Redisson.create(config);
            return new RedisBaseConditionCache(redisson, compressionThreshold);
        }
    }

    @Override
    public CompletableFuture<Optional<CacheEntry>> get(String cacheKey) {
        totalRequests.incrementAndGet();

        return CompletableFuture.supplyAsync(() -> {
            try {
                byte[] compressed = cacheMap.get(cacheKey);

                if (compressed == null) {
                    misses.incrementAndGet();
                    return Optional.empty();
                }

                // Decompress and deserialize
                BitSet result = deserializeBitSet(compressed);
                hits.incrementAndGet();

                // Note: We lose some metadata in Redis (like hit count)
                // Could store as a composite object if needed
                return Optional.of(new CacheEntry(
                        result,
                        System.nanoTime(), // Approximate
                        0, // Hit count not tracked in this simple impl
                        cacheKey
                ));

            } catch (Exception e) {
                logger.warning("Error getting cache entry: " + e.getMessage());
                misses.incrementAndGet();
                return Optional.empty();
            }
        });
    }

    @Override
    public CompletableFuture<Void> put(String cacheKey, BitSet result, long ttl, TimeUnit timeUnit) {
        return CompletableFuture.runAsync(() -> {
            try {
                byte[] serialized = serializeBitSet(result);

                // Store with TTL
                cacheMap.put(cacheKey, serialized, ttl, timeUnit);

                logger.fine(String.format(
                        "Cached to Redis: key=%s, size=%d bytes, ttl=%d %s",
                        cacheKey, serialized.length, ttl, timeUnit
                ));

            } catch (Exception e) {
                logger.warning("Error putting cache entry: " + e.getMessage());
            }
        });
    }

    @Override
    public CompletableFuture<Map<String, CacheEntry>> getBatch(Iterable<String> cacheKeys) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, CacheEntry> results = new HashMap<>();

            // Collect keys
            Set<String> keySet = new HashSet<>();
            cacheKeys.forEach(keySet::add);

            // Batch get from Redis
            Map<String, byte[]> cached = cacheMap.getAll(keySet);

            // Process results
            for (Map.Entry<String, byte[]> entry : cached.entrySet()) {
                if (entry.getValue() != null) {
                    try {
                        BitSet bitSet = deserializeBitSet(entry.getValue());
                        results.put(entry.getKey(), new CacheEntry(
                                bitSet,
                                System.nanoTime(),
                                0,
                                entry.getKey()
                        ));
                        hits.incrementAndGet();
                    } catch (Exception e) {
                        logger.warning("Error deserializing batch entry: " + e.getMessage());
                        misses.incrementAndGet();
                    }
                } else {
                    misses.incrementAndGet();
                }
            }

            totalRequests.addAndGet(keySet.size());
            return results;
        });
    }

    @Override
    public CompletableFuture<Void> invalidate(String cacheKey) {
        return CompletableFuture.runAsync(() -> {
            if (cacheMap.remove(cacheKey) != null) {
                evictions.incrementAndGet();
            }
        });
    }

    @Override
    public CompletableFuture<Void> clear() {
        return CompletableFuture.runAsync(() -> {
            int size = cacheMap.size();
            cacheMap.clear();
            evictions.addAndGet(size);
            logger.info("Redis cache cleared: " + size + " entries removed");
        });
    }

    @Override
    public CacheMetrics getMetrics() {
        long total = totalRequests.get();
        long hitCount = hits.get();
        long missCount = misses.get();

        return new CacheMetrics(
                total,
                hitCount,
                missCount,
                evictions.get(),
                cacheMap.size(),
                total > 0 ? (double) hitCount / total : 0.0,
                0, // Timing metrics would need more sophisticated tracking
                0
        );
    }

    /**
     * Serialize BitSet to byte array with optional compression.
     */
    private byte[] serializeBitSet(BitSet bitSet) throws IOException {
        byte[] raw = bitSet.toByteArray();

        if (raw.length <= compressionThreshold) {
            // No compression for small BitSets
            return raw;
        }

        // Compress using GZIP for larger BitSets
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             java.util.zip.GZIPOutputStream gzip = new java.util.zip.GZIPOutputStream(baos)) {

            gzip.write(raw);
            gzip.finish();

            byte[] compressed = baos.toByteArray();

            // Only use compression if it actually reduces size
            if (compressed.length < raw.length) {
                // Add compression marker (first byte = 1)
                byte[] result = new byte[compressed.length + 1];
                result[0] = 1; // Compressed marker
                System.arraycopy(compressed, 0, result, 1, compressed.length);
                return result;
            }
        }

        // Add uncompressed marker (first byte = 0)
        byte[] result = new byte[raw.length + 1];
        result[0] = 0; // Uncompressed marker
        System.arraycopy(raw, 0, result, 1, raw.length);
        return result;
    }

    /**
     * Deserialize byte array to BitSet with decompression if needed.
     */
    private BitSet deserializeBitSet(byte[] data) throws IOException {
        if (data.length == 0) {
            return new BitSet();
        }

        // Check compression marker
        boolean isCompressed = data[0] == 1;

        if (isCompressed) {
            // Decompress
            try (ByteArrayInputStream bais = new ByteArrayInputStream(data, 1, data.length - 1);
                 java.util.zip.GZIPInputStream gzip = new java.util.zip.GZIPInputStream(bais);
                 ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

                byte[] buffer = new byte[1024];
                int len;
                while ((len = gzip.read(buffer)) > 0) {
                    baos.write(buffer, 0, len);
                }

                return BitSet.valueOf(baos.toByteArray());
            }
        } else {
            // No compression, skip marker byte
            byte[] raw = new byte[data.length - 1];
            System.arraycopy(data, 1, raw, 0, raw.length);
            return BitSet.valueOf(raw);
        }
    }

    /**
     * Shutdown Redis connection gracefully.
     */
    public void shutdown() {
        if (redisson != null && !redisson.isShutdown()) {
            redisson.shutdown();
            logger.info("Redis connection shutdown");
        }
    }
}

/**
 * Example migration code to switch from in-memory to Redis cache:
 *
 * // Before (In-memory):
 * BaseConditionCache cache = new InMemoryBaseConditionCache.Builder()
 *     .maxSize(10_000)
 *     .defaultTtl(5, TimeUnit.MINUTES)
 *     .build();
 *
 * // After (Redis):
 * BaseConditionCache cache = new RedisBaseConditionCache.Builder()
 *     .redisAddress("redis://redis-server:6379")
 *     .password("your-redis-password")
 *     .connectionPoolSize(32)
 *     .compressionThreshold(512)
 *     .build();
 *
 * // The RuleEvaluator code remains unchanged!
 * RuleEvaluator evaluator = new RuleEvaluator(model, cache, true);
 */