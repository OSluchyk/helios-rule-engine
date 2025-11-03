package com.helios.ruleengine.core.cache;

import java.text.DecimalFormat;
import java.util.logging.Logger;

/**
 * Factory for creating cache instances from unified configuration.
 *
 * <p>This factory eliminates the need to manually construct cache builders
 * and provides a centralized point for cache instantiation. Supports creating
 * both wrapper cache instances (BaseConditionCache) and native cache instances
 * (Caffeine, Guava, Redis).
 *
 * <p><b>Standard Usage (BaseConditionCache wrappers):</b>
 * <pre>{@code
 * // Development
 * CacheConfig config = CacheConfig.forDevelopment();
 * BaseConditionCache cache = CacheFactory.create(config);
 *
 * // Production with env override
 * CacheConfig config = CacheConfig.fromEnvironment();
 * BaseConditionCache cache = CacheFactory.create(config);
 *
 * // Custom config
 * CacheConfig config = CacheConfig.builder()
 *     .cacheType(CacheConfig.CacheType.ADAPTIVE)
 *     .maxSize(200_000)
 *     .ttlMinutes(15)
 *     .build();
 * BaseConditionCache cache = CacheFactory.create(config);
 * }</pre>
 *
 * <p><b>Native Cache Usage (direct Caffeine/Redis):</b>
 * <pre>{@code
 * // Create native Caffeine cache
 * CacheConfig config = CacheConfig.loadDefault();
 * Cache<String, BitSet> caffeineCache = CacheFactory.createNativeCache(config);
 *
 * // Create native Redis cache
 * CacheConfig redisConfig = CacheConfig.forDistributed("redis://host:6379", "pass");
 * RMapCache<String, BitSet> redisCache = CacheFactory.createNativeCache(redisConfig);
 * }</pre>
 *
 * <p><b>Supported Cache Types:</b>
 * <ul>
 *   <li><b>IN_MEMORY</b> - Simple LRU cache for development/testing</li>
 *   <li><b>CAFFEINE</b> - High-performance W-TinyLFU cache for production</li>
 *   <li><b>ADAPTIVE</b> - Self-tuning cache for auto-scaling environments</li>
 *   <li><b>REDIS</b> - Distributed cache for multi-instance deployments</li>
 *   <li><b>NO_OP</b> - Disabled cache for testing/benchmarking</li>
 * </ul>
 *
 * @author Platform Engineering Team
 * @version 2.1.0
 * @since 2.0.0
 */
public final class CacheFactory {

    private static final Logger logger = Logger.getLogger(CacheFactory.class.getName());

    // Prevent instantiation
    private CacheFactory() {
        throw new AssertionError("CacheFactory should not be instantiated");
    }

    /**
     * Create cache instance from configuration.
     *
     * @param config Cache configuration
     * @return Configured cache instance
     * @throws IllegalArgumentException if cache type not supported
     * @throws RuntimeException if cache instantiation fails
     */
    public static BaseConditionCache create(CacheConfig config) {
        logger.info("Creating cache: " + config);

        try {
            BaseConditionCache cache = switch (config.getCacheType()) {
                case IN_MEMORY -> createInMemoryCache(config);
                case CAFFEINE -> createCaffeineCache(config);
                case ADAPTIVE -> createAdaptiveCache(config);
                case REDIS -> createRedisCache(config);
                case NO_OP -> createNoOpCache(config);
            };

            logger.info("Cache created successfully: " + config.getCacheType());
            return cache;

        } catch (Exception e) {
            String msg = "Failed to create cache: " + config.getCacheType();
            logger.severe(msg + " - " + e.getMessage());
            throw new RuntimeException(msg, e);
        }
    }

    /**
     * Create a wrapper cache using CacheConfig's native builder integration.
     *
     * <p>This method leverages the new integration methods in CacheConfig to create
     * Caffeine/Guava/Redis caches directly without going through custom wrapper classes.
     * Useful for scenarios where you want to use the raw cache libraries.
     *
     * <p><b>Example:</b>
     * <pre>{@code
     * CacheConfig config = CacheConfig.loadDefault();
     * com.github.benmanes.caffeine.cache.Cache<String, BitSet> cache =
     *     CacheFactory.createNativeCache(config);
     * }</pre>
     *
     * @param config Cache configuration
     * @param <K> Cache key type
     * @param <V> Cache value type
     * @return Native cache instance (Caffeine, Guava, or Redis)
     * @throws IllegalStateException if cache type doesn't support native creation
     * @since 2.1.0
     */
    public static <K, V> Object createNativeCache(CacheConfig config) {
        logger.info("Creating native cache: " + config);

        return switch (config.getCacheType()) {
            case CAFFEINE, ADAPTIVE -> {
                logger.info("Using CacheConfig.buildCache() for Caffeine cache");
                yield config.<K, V>buildCache();
            }
            case REDIS -> {
                logger.info("Using CacheConfig.buildRedisCache() for Redis cache");
                // For Redis, you need to specify the cache name
                String cacheName = System.getProperty("cache.redis.name", "baseConditions");
                yield config.<K, V>buildRedisCache(cacheName);
            }
            case IN_MEMORY, NO_OP -> throw new IllegalStateException(
                    "Native cache creation not supported for cache type: " + config.getCacheType() +
                            ". Use create(config) instead to get BaseConditionCache wrapper."
            );
        };
    }

    /**
     * Create InMemoryBaseConditionCache from config.
     *
     * <p>Simple LRU cache for development/testing. Not recommended for production.
     */
    public static BaseConditionCache createInMemoryCache(CacheConfig config) {
        logger.info(String.format(
                "InMemory cache configuration: maxSize=%d, ttl=%d %s",
                config.getMaxSize(),
                config.getTtlDuration(),
                config.getTtlUnit()
        ));

        return new InMemoryBaseConditionCache.Builder()
                .maxSize((int) config.getMaxSize())
                .defaultTtl(config.getTtlDuration(), config.getTtlUnit())
                .build();
    }

    /**
     * Create CaffeineBaseConditionCache from config.
     *
     * <p>Configures Caffeine cache with max size, TTL, stats, and initial capacity.
     */
    public static BaseConditionCache createCaffeineCache(CacheConfig config) {
        CaffeineBaseConditionCache.Builder builder = CaffeineBaseConditionCache.builder()
                .maxSize(config.getMaxSize())
                .expireAfterWrite(config.getTtlDuration(), config.getTtlUnit())
                .recordStats(config.isRecordStats());

        // Set initial capacity if specified
        if (config.getInitialCapacity() > 0) {
            builder.initialCapacity(config.getInitialCapacity());
        }

        logger.info(String.format(
                "Caffeine cache configuration: maxSize=%d, initialCapacity=%d, ttl=%d %s, recordStats=%s",
                config.getMaxSize(),
                config.getInitialCapacity(),
                config.getTtlDuration(),
                config.getTtlUnit(),
                config.isRecordStats()
        ));

        return builder.build();
    }

    /**
     * Create AdaptiveCaffeineCache from config.
     *
     * <p>Note: AdaptiveCaffeineCache uses internal constants for tuning parameters.
     * The config values for minCacheSize, maxCacheSize, thresholds, and tuning interval
     * are logged but not applied (they are hard-coded in AdaptiveCaffeineCache).
     */
    public static BaseConditionCache createAdaptiveCache(CacheConfig config) {
        AdaptiveCaffeineCache.Builder builder = AdaptiveCaffeineCache.builder()
                .initialMaxSize(config.getMaxSize())
                .expireAfterWrite(config.getTtlDuration(), config.getTtlUnit())
                .recordStats(config.isRecordStats())
                .enableAdaptiveSizing(config.isEnableAdaptiveSizing());

        if (config.isEnableAdaptiveSizing()) {
            DecimalFormat df = new DecimalFormat("0.00");

            String msg = new StringBuilder(256)
                    .append("Adaptive cache created: initialSize=").append(config.getMaxSize())
                    .append(", ttl=").append(config.getTtlDuration()).append(' ').append(config.getTtlUnit())
                    .append(", recordStats=").append(config.isRecordStats()).append(". ")
                    .append("Note: Adaptive tuning uses internal constants (minSize=10K, maxSize=10M, ")
                    .append("lowThreshold=0.70, highThreshold=0.95, interval=30s). ")
                    .append("Config values (minSize=").append(config.getMinCacheSize())
                    .append(", maxSize=").append(config.getMaxCacheSize())
                    .append(", lowThreshold=").append(df.format(config.getLowHitRateThreshold()))
                    .append(", highThreshold=").append(df.format(config.getHighHitRateThreshold()))
                    .append(", interval=").append(config.getTuningIntervalSeconds()).append("s) are logged but not applied.")
                    .toString();

            logger.info(msg);
        } else {
            logger.info(String.format(
                    "Adaptive cache created with adaptive sizing disabled: maxSize=%d, ttl=%d %s",
                    config.getMaxSize(),
                    config.getTtlDuration(),
                    config.getTtlUnit()
            ));
        }

        return builder.build();
    }

    /**
     * Create RedisBaseConditionCache from config.
     *
     * <p>Note: RedisBaseConditionCache.Builder does not expose connectionMinimumIdleSize
     * and timeout setter methods, though these values are used internally with defaults
     * (minIdleSize=24, timeout=3000ms). To customize these, the Builder class needs to be
     * extended with these setter methods.
     */
    public static BaseConditionCache createRedisCache(CacheConfig config) {
        RedisBaseConditionCache.Builder builder = new RedisBaseConditionCache.Builder()
                .redisAddress(config.getRedisAddress())
                .connectionPoolSize(config.getRedisConnectionPoolSize())
                .compressionThreshold(config.getRedisCompressionThreshold())
                .useCluster(config.isRedisUseCluster());

        // Set password if provided
        if (config.getRedisPassword() != null && !config.getRedisPassword().isEmpty()) {
            builder.password(config.getRedisPassword());
        }

        logger.info(String.format(
                "Redis cache configuration: address=%s, poolSize=%d, compression=%d bytes, cluster=%s. " +
                        "Note: minIdleSize and timeout use defaults (minIdleSize=24, timeout=3000ms) - " +
                        "config values (minIdle=%d, timeout=%dms) cannot be applied without extending Builder.",
                config.getRedisAddress(),
                config.getRedisConnectionPoolSize(),
                config.getRedisCompressionThreshold(),
                config.isRedisUseCluster(),
                config.getRedisMinIdleSize(),
                config.getRedisTimeoutMs()
        ));

        return builder.build();
    }

    /**
     * Create NoOpCache from config.
     */
    public static BaseConditionCache createNoOpCache(CacheConfig config) {
        // NoOpCache doesn't use any configuration parameters
        logger.info("Creating NoOpCache (all operations are no-ops, 0% cache hit rate)");
        return new NoOpCache();
    }

    /**
     * Create cache with automatic type selection based on environment.
     *
     * <p>Priority order:
     * <ol>
     *   <li>CACHE_TYPE environment variable</li>
     *   <li>Fallback to CAFFEINE (production default)</li>
     * </ol>
     */
    public static BaseConditionCache createFromEnvironment() {
        CacheConfig config = CacheConfig.fromEnvironment();
        return create(config);
    }

    /**
     * Create cache with sensible defaults for given environment type.
     *
     * @param environment "development", "production", "adaptive", or "distributed"
     * @return Configured cache instance
     */
    public static BaseConditionCache createForEnvironment(String environment) {
        CacheConfig config = switch (environment.toLowerCase()) {
            case "development", "dev" -> CacheConfig.forDevelopment();
            case "production", "prod" -> CacheConfig.forProduction();
            case "adaptive", "auto-scale" -> CacheConfig.forAdaptiveProduction();
            case "distributed", "redis" -> CacheConfig.forDistributed(
                    System.getenv().getOrDefault("CACHE_REDIS_ADDRESS", "redis://localhost:6379"),
                    System.getenv("CACHE_REDIS_PASSWORD")
            );
            default -> throw new IllegalArgumentException(
                    "Unknown environment: " + environment
                            + ". Valid values: development, production, adaptive, distributed"
            );
        };

        return create(config);
    }
}