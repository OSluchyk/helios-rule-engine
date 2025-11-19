package com.helios.ruleengine.infra.cache;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Unified configuration for all cache implementations.
 *
 * <p>This class consolidates configuration properties from:
 * <ul>
 *   <li>{@link InMemoryBaseConditionCache}</li>
 *   <li>{@link CaffeineBaseConditionCache}</li>
 *   <li>{@link AdaptiveCaffeineCache}</li>
 *   <li>{@link RedisBaseConditionCache}</li>
 * </ul>
 *
 * <p><b>Environment Variable Override:</b>
 * Every property can be overridden via environment variables using the pattern:
 * {@code CACHE_<PROPERTY_NAME>}
 *
 * <p>Example environment variables:
 * <pre>
 * CACHE_MAX_SIZE=200000
 * CACHE_TTL_MINUTES=10
 * CACHE_REDIS_ADDRESS=redis://prod-redis:6379
 * CACHE_REDIS_PASSWORD=secret
 * CACHE_ENABLE_ADAPTIVE_SIZING=true
 * </pre>
 *
 * <p><b>Usage Example:</b>
 * <pre>{@code
 * // Development config (InMemory)
 * CacheConfig config = CacheConfig.forDevelopment();
 *
 * // Production config (Caffeine)
 * CacheConfig config = CacheConfig.forProduction();
 *
 * // Custom config with env override
 * CacheConfig config = CacheConfig.builder()
 *     .maxSize(100_000)
 *     .ttlMinutes(10)
 *     .recordStats(true)
 *     .build();
 *
 * // Create cache from config
 * BaseConditionCache cache = CacheFactory.create(config);
 * }</pre>
 *
 * @author Platform Engineering Team
 * @since 2.0.0
 */
public final class CacheConfig {

    private static final Logger logger = Logger.getLogger(CacheConfig.class.getName());

    // ========================================================================
    // ENVIRONMENT VARIABLE KEYS
    // ========================================================================

    private static final String ENV_CACHE_TYPE = "CACHE_TYPE";

    // Common properties
    private static final String ENV_MAX_SIZE = "CACHE_MAX_SIZE";
    private static final String ENV_INITIAL_CAPACITY = "CACHE_INITIAL_CAPACITY";
    private static final String ENV_TTL_MINUTES = "CACHE_TTL_MINUTES";
    private static final String ENV_TTL_SECONDS = "CACHE_TTL_SECONDS";
    private static final String ENV_RECORD_STATS = "CACHE_RECORD_STATS";

    // Adaptive properties
    private static final String ENV_ENABLE_ADAPTIVE_SIZING = "CACHE_ENABLE_ADAPTIVE_SIZING";
    private static final String ENV_MIN_CACHE_SIZE = "CACHE_MIN_CACHE_SIZE";
    private static final String ENV_MAX_CACHE_SIZE = "CACHE_MAX_CACHE_SIZE";
    private static final String ENV_LOW_HIT_RATE_THRESHOLD = "CACHE_LOW_HIT_RATE_THRESHOLD";
    private static final String ENV_HIGH_HIT_RATE_THRESHOLD = "CACHE_HIGH_HIT_RATE_THRESHOLD";
    private static final String ENV_TUNING_INTERVAL_SECONDS = "CACHE_TUNING_INTERVAL_SECONDS";

    // Redis properties
    private static final String ENV_REDIS_ADDRESS = "CACHE_REDIS_ADDRESS";
    private static final String ENV_REDIS_PASSWORD = "CACHE_REDIS_PASSWORD";
    private static final String ENV_REDIS_CONNECTION_POOL_SIZE = "CACHE_REDIS_CONNECTION_POOL_SIZE";
    private static final String ENV_REDIS_MIN_IDLE_SIZE = "CACHE_REDIS_MIN_IDLE_SIZE";
    private static final String ENV_REDIS_TIMEOUT_MS = "CACHE_REDIS_TIMEOUT_MS";
    private static final String ENV_REDIS_COMPRESSION_THRESHOLD = "CACHE_REDIS_COMPRESSION_THRESHOLD";
    private static final String ENV_REDIS_USE_CLUSTER = "CACHE_REDIS_USE_CLUSTER";

    // ========================================================================
    // CACHE TYPE ENUM
    // ========================================================================

    /**
     * Supported cache implementation types.
     */
    public enum CacheType {
        /** In-memory cache with LRU eviction (development) */
        IN_MEMORY,

        /** Caffeine cache with W-TinyLFU eviction (production single-node) */
        CAFFEINE,

        /** Adaptive Caffeine cache with auto-sizing (production auto-scale) */
        ADAPTIVE,

        /** Redis distributed cache (production multi-instance) */
        REDIS,

        /** No-operation cache (testing, benchmarking) */
        NO_OP
    }

    // ========================================================================
    // CONFIGURATION FIELDS
    // ========================================================================

    // Cache type selection
    private final CacheType cacheType;

    // Common properties (all caches)
    private final long maxSize;
    private final int initialCapacity;
    private final long ttlDuration;
    private final TimeUnit ttlUnit;
    private final boolean recordStats;

    // Adaptive properties
    private final boolean enableAdaptiveSizing;
    private final long minCacheSize;
    private final long maxCacheSize;
    private final double lowHitRateThreshold;
    private final double highHitRateThreshold;
    private final long tuningIntervalSeconds;

    // Redis properties
    private final String redisAddress;
    private final String redisPassword;
    private final int redisConnectionPoolSize;
    private final int redisMinIdleSize;
    private final int redisTimeoutMs;
    private final int redisCompressionThreshold;
    private final boolean redisUseCluster;

    // ========================================================================
    // PRIVATE CONSTRUCTOR (use Builder)
    // ========================================================================

    private CacheConfig(Builder builder) {
        this.cacheType = builder.cacheType;

        // Common properties
        this.maxSize = builder.maxSize;
        this.initialCapacity = builder.initialCapacity;
        this.ttlDuration = builder.ttlDuration;
        this.ttlUnit = builder.ttlUnit;
        this.recordStats = builder.recordStats;

        // Adaptive properties
        this.enableAdaptiveSizing = builder.enableAdaptiveSizing;
        this.minCacheSize = builder.minCacheSize;
        this.maxCacheSize = builder.maxCacheSize;
        this.lowHitRateThreshold = builder.lowHitRateThreshold;
        this.highHitRateThreshold = builder.highHitRateThreshold;
        this.tuningIntervalSeconds = builder.tuningIntervalSeconds;

        // Redis properties
        this.redisAddress = builder.redisAddress;
        this.redisPassword = builder.redisPassword;
        this.redisConnectionPoolSize = builder.redisConnectionPoolSize;
        this.redisMinIdleSize = builder.redisMinIdleSize;
        this.redisTimeoutMs = builder.redisTimeoutMs;
        this.redisCompressionThreshold = builder.redisCompressionThreshold;
        this.redisUseCluster = builder.redisUseCluster;

        validate();
    }

    // ========================================================================
    // FACTORY METHODS
    // ========================================================================

    /**
     * Create development configuration (InMemory cache).
     *
     * <p>Optimized for:
     * <ul>
     *   <li>Fast restart cycles</li>
     *   <li>No external dependencies</li>
     *   <li>Small rule sets (&lt;10K rules)</li>
     * </ul>
     */
    public static CacheConfig forDevelopment() {
        return builder()
                .cacheType(CacheType.IN_MEMORY)
                .maxSize(10_000)
                .ttl(5, TimeUnit.MINUTES)
                .recordStats(false)
                .build();
    }

    /**
     * Create production configuration (Caffeine cache).
     *
     * <p>Optimized for:
     * <ul>
     *   <li>Single-node deployments</li>
     *   <li>75-85% hit rate</li>
     *   <li>Predictable memory footprint</li>
     * </ul>
     */
    public static CacheConfig forProduction() {
        return builder()
                .cacheType(CacheType.CAFFEINE)
                .maxSize(100_000)
                .initialCapacity(10_000)
                .ttl(10, TimeUnit.MINUTES)
                .recordStats(true)
                .build();
    }

    /**
     * Create adaptive production configuration (AdaptiveCaffeine cache).
     *
     * <p>Optimized for:
     * <ul>
     *   <li>Auto-scaling workloads</li>
     *   <li>85-95% hit rate</li>
     *   <li>Dynamic traffic patterns</li>
     * </ul>
     */
    public static CacheConfig forAdaptiveProduction() {
        return builder()
                .cacheType(CacheType.ADAPTIVE)
                .maxSize(100_000)  // Initial size
                .ttl(5, TimeUnit.MINUTES)
                .recordStats(true)
                .enableAdaptiveSizing(true)
                .minCacheSize(10_000)
                .maxCacheSize(10_000_000)
                .lowHitRateThreshold(0.70)
                .highHitRateThreshold(0.95)
                .tuningIntervalSeconds(30)
                .build();
    }

    /**
     * Create distributed configuration (Redis cache).
     *
     * <p>Optimized for:
     * <ul>
     *   <li>Multi-instance deployments</li>
     *   <li>70-80% hit rate</li>
     *   <li>Shared cache across services</li>
     * </ul>
     *
     * @param redisAddress Redis connection string (e.g., "redis://host:6379")
     * @param password Redis password (null if no auth)
     */
    public static CacheConfig forDistributed(String redisAddress, String password) {
        return builder()
                .cacheType(CacheType.REDIS)
                .redisAddress(redisAddress)
                .redisPassword(password)
                .redisConnectionPoolSize(32)
                .redisMinIdleSize(16)
                .redisTimeoutMs(2000)
                .redisCompressionThreshold(512)
                .redisUseCluster(false)
                .ttl(10, TimeUnit.MINUTES)
                .recordStats(true)
                .build();
    }

    /**
     * Create configuration from environment variables only.
     *
     * <p>Falls back to production defaults if env vars not set.
     */
    public static CacheConfig fromEnvironment() {
        return builder().build();  // Builder auto-reads env vars
    }

    /**
     * Load default configuration from classpath properties file.
     *
     * <p>Looks for {@code cache.properties} in classpath root.
     * Falls back to production defaults if file not found.
     *
     * <p>Environment variables override properties file values.
     *
     * <p><b>Example cache.properties:</b>
     * <pre>
     * cache.type=ADAPTIVE
     * cache.max.size=250000
     * cache.ttl.minutes=10
     * cache.record.stats=true
     * cache.adaptive.enabled=true
     * cache.adaptive.min.size=100000
     * cache.adaptive.max.size=5000000
     * cache.redis.address=redis://localhost:6379
     * </pre>
     *
     * @return Configuration loaded from properties file
     */
    public static CacheConfig loadDefault() {
        return loadFromProperties("cache.properties");
    }

    /**
     * Load configuration from specific properties file.
     *
     * <p>Searches for file in:
     * <ol>
     *   <li>Classpath root</li>
     *   <li>File system (absolute or relative path)</li>
     * </ol>
     *
     * <p>Environment variables override properties file values.
     *
     * @param propertiesPath Path to properties file
     * @return Configuration loaded from properties file
     */
    public static CacheConfig loadFromProperties(String propertiesPath) {
        logger.info("Loading cache configuration from: " + propertiesPath);

        java.util.Properties props = new java.util.Properties();

        // Try classpath first
        try (java.io.InputStream is = CacheConfig.class.getClassLoader()
                .getResourceAsStream(propertiesPath)) {
            if (is != null) {
                props.load(is);
                logger.info("Loaded " + props.size() + " properties from classpath: " + propertiesPath);
            }
        } catch (Exception e) {
            logger.fine("Could not load from classpath: " + propertiesPath);
        }

        // Try file system if not found in classpath
        if (props.isEmpty()) {
            try (java.io.FileInputStream fis = new java.io.FileInputStream(propertiesPath)) {
                props.load(fis);
                logger.info("Loaded " + props.size() + " properties from file: " + propertiesPath);
            } catch (Exception e) {
                logger.warning("Could not load properties file: " + propertiesPath +
                        ". Using defaults.");
            }
        }

        // Build configuration from properties
        return builderFromProperties(props).build();
    }

    /**
     * Create builder from properties.
     * Environment variables still take precedence.
     */
    private static Builder builderFromProperties(java.util.Properties props) {
        Builder builder = builder();  // This auto-loads env vars

        // Override builder defaults with properties file values
        // (but env vars will still override in the builder constructor)

        // Cache type
        String type = props.getProperty("cache.type");
        if (type != null) {
            try {
                builder.cacheType = CacheType.valueOf(type.toUpperCase());
            } catch (IllegalArgumentException e) {
                logger.warning("Invalid cache.type in properties: " + type);
            }
        }

        // Common properties
        String maxSize = props.getProperty("cache.max.size");
        if (maxSize != null) {
            builder.maxSize = Long.parseLong(maxSize);
        }

        String initialCapacity = props.getProperty("cache.initial.capacity");
        if (initialCapacity != null) {
            builder.initialCapacity = Integer.parseInt(initialCapacity);
        }

        String ttlMinutes = props.getProperty("cache.ttl.minutes");
        if (ttlMinutes != null) {
            builder.ttlDuration = Long.parseLong(ttlMinutes);
            builder.ttlUnit = TimeUnit.MINUTES;
        }

        String ttlSeconds = props.getProperty("cache.ttl.seconds");
        if (ttlSeconds != null) {
            builder.ttlDuration = Long.parseLong(ttlSeconds);
            builder.ttlUnit = TimeUnit.SECONDS;
        }

        String recordStats = props.getProperty("cache.record.stats");
        if (recordStats != null) {
            builder.recordStats = Boolean.parseBoolean(recordStats);
        }

        // Adaptive properties
        String adaptiveEnabled = props.getProperty("cache.adaptive.enabled");
        if (adaptiveEnabled != null) {
            builder.enableAdaptiveSizing = Boolean.parseBoolean(adaptiveEnabled);
        }

        // Support for adaptive.initial.max.size (overrides common max.size for adaptive caches)
        String adaptiveInitialMaxSize = props.getProperty("cache.adaptive.initial.max.size");
        if (adaptiveInitialMaxSize != null) {
            builder.maxSize = Long.parseLong(adaptiveInitialMaxSize);
        }

        String minCacheSize = props.getProperty("cache.adaptive.min.size");
        if (minCacheSize != null) {
            builder.minCacheSize = Long.parseLong(minCacheSize);
        }

        String maxCacheSize = props.getProperty("cache.adaptive.max.size");
        if (maxCacheSize != null) {
            builder.maxCacheSize = Long.parseLong(maxCacheSize);
        }

        String lowThreshold = props.getProperty("cache.adaptive.low.threshold");
        if (lowThreshold != null) {
            builder.lowHitRateThreshold = Double.parseDouble(lowThreshold);
        }

        String highThreshold = props.getProperty("cache.adaptive.high.threshold");
        if (highThreshold != null) {
            builder.highHitRateThreshold = Double.parseDouble(highThreshold);
        }

        String tuningInterval = props.getProperty("cache.adaptive.tuning.interval.seconds");
        if (tuningInterval != null) {
            builder.tuningIntervalSeconds = Long.parseLong(tuningInterval);
        }

        // Redis properties
        String redisAddress = props.getProperty("cache.redis.address");
        if (redisAddress != null) {
            builder.redisAddress = redisAddress;
        }

        String redisPassword = props.getProperty("cache.redis.password");
        if (redisPassword != null) {
            builder.redisPassword = redisPassword;
        }

        String poolSize = props.getProperty("cache.redis.pool.size");
        if (poolSize != null) {
            builder.redisConnectionPoolSize = Integer.parseInt(poolSize);
        }

        String minIdle = props.getProperty("cache.redis.min.idle");
        if (minIdle != null) {
            builder.redisMinIdleSize = Integer.parseInt(minIdle);
        }

        String timeout = props.getProperty("cache.redis.timeout.ms");
        if (timeout != null) {
            builder.redisTimeoutMs = Integer.parseInt(timeout);
        }

        String compression = props.getProperty("cache.redis.compression.threshold");
        if (compression != null) {
            builder.redisCompressionThreshold = Integer.parseInt(compression);
        }

        String useCluster = props.getProperty("cache.redis.use.cluster");
        if (useCluster != null) {
            builder.redisUseCluster = Boolean.parseBoolean(useCluster);
        }

        return builder;
    }

    // ========================================================================
    // BUILDER
    // ========================================================================

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Create a builder initialized with this configuration's values.
     *
     * <p>Useful for creating modified configurations:
     * <pre>{@code
     * // Load defaults and override specific properties
     * CacheConfig config = CacheConfig.loadDefault()
     *     .toBuilder()
     *     .maxSize(500_000)
     *     .ttlMinutes(15)
     *     .build();
     *
     * // Clone and modify
     * CacheConfig prodConfig = CacheConfig.forProduction();
     * CacheConfig customConfig = prodConfig.toBuilder()
     *     .recordStats(false)
     *     .build();
     * }</pre>
     *
     * @return New builder initialized with this config's values
     */
    public Builder toBuilder() {
        Builder builder = new Builder();

        // Copy all values (skip env var loading)
        builder.skipEnvironmentLoading = true;

        // Copy current values
        builder.cacheType = this.cacheType;
        builder.maxSize = this.maxSize;
        builder.initialCapacity = this.initialCapacity;
        builder.ttlDuration = this.ttlDuration;
        builder.ttlUnit = this.ttlUnit;
        builder.recordStats = this.recordStats;

        builder.enableAdaptiveSizing = this.enableAdaptiveSizing;
        builder.minCacheSize = this.minCacheSize;
        builder.maxCacheSize = this.maxCacheSize;
        builder.lowHitRateThreshold = this.lowHitRateThreshold;
        builder.highHitRateThreshold = this.highHitRateThreshold;
        builder.tuningIntervalSeconds = this.tuningIntervalSeconds;

        builder.redisAddress = this.redisAddress;
        builder.redisPassword = this.redisPassword;
        builder.redisConnectionPoolSize = this.redisConnectionPoolSize;
        builder.redisMinIdleSize = this.redisMinIdleSize;
        builder.redisTimeoutMs = this.redisTimeoutMs;
        builder.redisCompressionThreshold = this.redisCompressionThreshold;
        builder.redisUseCluster = this.redisUseCluster;

        return builder;
    }

    public static class Builder {

        // Internal flag to skip env var loading (used by toBuilder)
        private boolean skipEnvironmentLoading = false;

        // Defaults (can be overridden by env vars or builder methods)
        private CacheType cacheType = CacheType.CAFFEINE;
        private long maxSize = 100_000;
        private int initialCapacity = 0;
        private long ttlDuration = 10;
        private TimeUnit ttlUnit = TimeUnit.MINUTES;
        private boolean recordStats = true;

        // Adaptive defaults
        private boolean enableAdaptiveSizing = false;
        private long minCacheSize = 10_000;
        private long maxCacheSize = 10_000_000;
        private double lowHitRateThreshold = 0.70;
        private double highHitRateThreshold = 0.95;
        private long tuningIntervalSeconds = 30;

        // Redis defaults
        private String redisAddress = "redis://localhost:6379";
        private String redisPassword = null;
        private int redisConnectionPoolSize = 64;
        private int redisMinIdleSize = 24;
        private int redisTimeoutMs = 3000;
        private int redisCompressionThreshold = 1024;
        private boolean redisUseCluster = false;

        private Builder() {
            // Apply environment variable overrides (unless skipped by toBuilder)
            if (!skipEnvironmentLoading) {
                applyEnvironmentVariables();
            }
        }

        /**
         * Apply environment variable overrides to all properties.
         */
        private void applyEnvironmentVariables() {
            // Cache type
            getEnv(ENV_CACHE_TYPE).ifPresent(val -> {
                try {
                    this.cacheType = CacheType.valueOf(val.toUpperCase());
                } catch (IllegalArgumentException e) {
                    logger.warning("Invalid CACHE_TYPE: " + val + ", using default: " + this.cacheType);
                }
            });

            // Common properties
            getEnvLong(ENV_MAX_SIZE).ifPresent(val -> this.maxSize = val);
            getEnvInt(ENV_INITIAL_CAPACITY).ifPresent(val -> this.initialCapacity = val);
            getEnvLong(ENV_TTL_MINUTES).ifPresent(val -> {
                this.ttlDuration = val;
                this.ttlUnit = TimeUnit.MINUTES;
            });
            getEnvLong(ENV_TTL_SECONDS).ifPresent(val -> {
                this.ttlDuration = val;
                this.ttlUnit = TimeUnit.SECONDS;
            });
            getEnvBoolean(ENV_RECORD_STATS).ifPresent(val -> this.recordStats = val);

            // Adaptive properties
            getEnvBoolean(ENV_ENABLE_ADAPTIVE_SIZING).ifPresent(val -> this.enableAdaptiveSizing = val);
            getEnvLong(ENV_MIN_CACHE_SIZE).ifPresent(val -> this.minCacheSize = val);
            getEnvLong(ENV_MAX_CACHE_SIZE).ifPresent(val -> this.maxCacheSize = val);
            getEnvDouble(ENV_LOW_HIT_RATE_THRESHOLD).ifPresent(val -> this.lowHitRateThreshold = val);
            getEnvDouble(ENV_HIGH_HIT_RATE_THRESHOLD).ifPresent(val -> this.highHitRateThreshold = val);
            getEnvLong(ENV_TUNING_INTERVAL_SECONDS).ifPresent(val -> this.tuningIntervalSeconds = val);

            // Redis properties
            getEnv(ENV_REDIS_ADDRESS).ifPresent(val -> this.redisAddress = val);
            getEnv(ENV_REDIS_PASSWORD).ifPresent(val -> this.redisPassword = val);
            getEnvInt(ENV_REDIS_CONNECTION_POOL_SIZE).ifPresent(val -> this.redisConnectionPoolSize = val);
            getEnvInt(ENV_REDIS_MIN_IDLE_SIZE).ifPresent(val -> this.redisMinIdleSize = val);
            getEnvInt(ENV_REDIS_TIMEOUT_MS).ifPresent(val -> this.redisTimeoutMs = val);
            getEnvInt(ENV_REDIS_COMPRESSION_THRESHOLD).ifPresent(val -> this.redisCompressionThreshold = val);
            getEnvBoolean(ENV_REDIS_USE_CLUSTER).ifPresent(val -> this.redisUseCluster = val);
        }

        // ====================================================================
        // BUILDER METHODS
        // ====================================================================

        public Builder cacheType(CacheType type) {
            this.cacheType = type;
            return this;
        }

        // Common properties

        public Builder maxSize(long size) {
            this.maxSize = size;
            return this;
        }

        public Builder initialCapacity(int capacity) {
            this.initialCapacity = capacity;
            return this;
        }

        public Builder ttl(long duration, TimeUnit unit) {
            this.ttlDuration = duration;
            this.ttlUnit = unit;
            return this;
        }

        public Builder ttlMinutes(long minutes) {
            return ttl(minutes, TimeUnit.MINUTES);
        }

        public Builder ttlSeconds(long seconds) {
            return ttl(seconds, TimeUnit.SECONDS);
        }

        public Builder recordStats(boolean enable) {
            this.recordStats = enable;
            return this;
        }

        // Adaptive properties

        public Builder enableAdaptiveSizing(boolean enable) {
            this.enableAdaptiveSizing = enable;
            return this;
        }

        public Builder minCacheSize(long size) {
            this.minCacheSize = size;
            return this;
        }

        public Builder maxCacheSize(long size) {
            this.maxCacheSize = size;
            return this;
        }

        public Builder lowHitRateThreshold(double threshold) {
            this.lowHitRateThreshold = threshold;
            return this;
        }

        public Builder highHitRateThreshold(double threshold) {
            this.highHitRateThreshold = threshold;
            return this;
        }

        public Builder tuningIntervalSeconds(long seconds) {
            this.tuningIntervalSeconds = seconds;
            return this;
        }

        // Redis properties

        public Builder redisAddress(String address) {
            this.redisAddress = address;
            return this;
        }

        public Builder redisPassword(String password) {
            this.redisPassword = password;
            return this;
        }

        public Builder redisConnectionPoolSize(int size) {
            this.redisConnectionPoolSize = size;
            return this;
        }

        public Builder redisMinIdleSize(int size) {
            this.redisMinIdleSize = size;
            return this;
        }

        public Builder redisTimeoutMs(int timeoutMs) {
            this.redisTimeoutMs = timeoutMs;
            return this;
        }

        public Builder redisCompressionThreshold(int thresholdBytes) {
            this.redisCompressionThreshold = thresholdBytes;
            return this;
        }

        public Builder redisUseCluster(boolean useCluster) {
            this.redisUseCluster = useCluster;
            return this;
        }

        public CacheConfig build() {
            return new CacheConfig(this);
        }

        // ====================================================================
        // ENVIRONMENT VARIABLE HELPERS
        // ====================================================================

        private static Optional<String> getEnv(String key) {
            String value = System.getenv(key);
            if (value != null && !value.trim().isEmpty()) {
                logger.fine("Loaded env var: " + key + "=" + maskSensitive(key, value));
                return Optional.of(value.trim());
            }
            return Optional.empty();
        }

        private static Optional<Long> getEnvLong(String key) {
            return getEnv(key).map(val -> {
                try {
                    return Long.parseLong(val);
                } catch (NumberFormatException e) {
                    logger.warning("Invalid long value for " + key + ": " + val);
                    return null;
                }
            });
        }

        private static Optional<Integer> getEnvInt(String key) {
            return getEnv(key).map(val -> {
                try {
                    return Integer.parseInt(val);
                } catch (NumberFormatException e) {
                    logger.warning("Invalid int value for " + key + ": " + val);
                    return null;
                }
            });
        }

        private static Optional<Double> getEnvDouble(String key) {
            return getEnv(key).map(val -> {
                try {
                    return Double.parseDouble(val);
                } catch (NumberFormatException e) {
                    logger.warning("Invalid double value for " + key + ": " + val);
                    return null;
                }
            });
        }

        private static Optional<Boolean> getEnvBoolean(String key) {
            return getEnv(key).map(val -> {
                String normalized = val.toLowerCase();
                return "true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized);
            });
        }

        private static String maskSensitive(String key, String value) {
            if (key.contains("PASSWORD") || key.contains("SECRET")) {
                return "***REDACTED***";
            }
            return value;
        }
    }

    // ========================================================================
    // VALIDATION
    // ========================================================================

    private void validate() {
        // Common validations
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize must be positive: " + maxSize);
        }
        if (ttlDuration <= 0) {
            throw new IllegalArgumentException("ttlDuration must be positive: " + ttlDuration);
        }

        // Adaptive validations
        if (enableAdaptiveSizing) {
            if (minCacheSize <= 0 || minCacheSize > maxCacheSize) {
                throw new IllegalArgumentException(
                        "Invalid adaptive cache size range: min=" + minCacheSize + ", max=" + maxCacheSize);
            }
            if (lowHitRateThreshold < 0 || lowHitRateThreshold > 1) {
                throw new IllegalArgumentException(
                        "lowHitRateThreshold must be between 0 and 1: " + lowHitRateThreshold);
            }
            if (highHitRateThreshold < 0 || highHitRateThreshold > 1) {
                throw new IllegalArgumentException(
                        "highHitRateThreshold must be between 0 and 1: " + highHitRateThreshold);
            }
            if (lowHitRateThreshold >= highHitRateThreshold) {
                throw new IllegalArgumentException(
                        "lowHitRateThreshold must be < highHitRateThreshold: "
                                + lowHitRateThreshold + " >= " + highHitRateThreshold);
            }
        }

        // Redis validations
        if (cacheType == CacheType.REDIS) {
            if (redisAddress == null || redisAddress.isEmpty()) {
                throw new IllegalArgumentException("redisAddress is required for REDIS cache type");
            }
            if (redisConnectionPoolSize <= 0) {
                throw new IllegalArgumentException(
                        "redisConnectionPoolSize must be positive: " + redisConnectionPoolSize);
            }
            if (redisTimeoutMs <= 0) {
                throw new IllegalArgumentException(
                        "redisTimeoutMs must be positive: " + redisTimeoutMs);
            }
        }

        logger.info("Cache configuration validated: " + this);
    }

    // ========================================================================
    // GETTERS
    // ========================================================================

    public CacheType getCacheType() { return cacheType; }

    // Common properties
    public long getMaxSize() { return maxSize; }
    public int getInitialCapacity() { return initialCapacity; }
    public long getTtlDuration() { return ttlDuration; }
    public TimeUnit getTtlUnit() { return ttlUnit; }
    public boolean isRecordStats() { return recordStats; }

    // TTL convenience methods
    public long getTtlMillis() { return ttlUnit.toMillis(ttlDuration); }
    public Duration getTtlAsDuration() { return Duration.of(ttlDuration, ttlUnit.toChronoUnit()); }

    // Adaptive properties
    public boolean isEnableAdaptiveSizing() { return enableAdaptiveSizing; }
    public long getMinCacheSize() { return minCacheSize; }
    public long getMaxCacheSize() { return maxCacheSize; }
    public double getLowHitRateThreshold() { return lowHitRateThreshold; }
    public double getHighHitRateThreshold() { return highHitRateThreshold; }
    public long getTuningIntervalSeconds() { return tuningIntervalSeconds; }
    public Duration getTuningInterval() { return Duration.ofSeconds(tuningIntervalSeconds); }

    // Redis properties
    public String getRedisAddress() { return redisAddress; }
    public String getRedisPassword() { return redisPassword; }
    public int getRedisConnectionPoolSize() { return redisConnectionPoolSize; }
    public int getRedisMinIdleSize() { return redisMinIdleSize; }
    public int getRedisTimeoutMs() { return redisTimeoutMs; }
    public int getRedisCompressionThreshold() { return redisCompressionThreshold; }
    public boolean isRedisUseCluster() { return redisUseCluster; }

    // ========================================================================
    // CACHE BUILDER INTEGRATION
    // ========================================================================

    /**
     * Convert this configuration to a Caffeine CacheBuilder.
     *
     * <p><b>Requires:</b> Caffeine library on classpath
     * <p><b>Example:</b>
     * <pre>{@code
     * CacheConfig config = CacheConfig.loadDefault();
     * Cache<String, BitSet> cache = config.toCaffeineBuilder()
     *     .build();
     * }</pre>
     *
     * @param <K> Cache key type
     * @param <V> Cache value type
     * @return Configured Caffeine CacheBuilder
     * @throws IllegalStateException if Caffeine not available on classpath
     */
    @SuppressWarnings("unchecked")
    public <K, V> com.github.benmanes.caffeine.cache.Caffeine<K, V> toCaffeineBuilder() {
        try {
            Class.forName("com.github.benmanes.caffeine.cache.Caffeine");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    "Caffeine library not found on classpath. Add dependency:\n" +
                            "<dependency>\n" +
                            "  <groupId>com.github.ben-manes.caffeine</groupId>\n" +
                            "  <artifactId>caffeine</artifactId>\n" +
                            "  <version>3.1.8</version>\n" +
                            "</dependency>", e);
        }

        com.github.benmanes.caffeine.cache.Caffeine<K, V> builder =
                (com.github.benmanes.caffeine.cache.Caffeine<K, V>) com.github.benmanes.caffeine.cache.Caffeine.newBuilder();

        // Set maximum size
        builder.maximumSize(maxSize);

        // Set initial capacity if specified
        if (initialCapacity > 0) {
            builder.initialCapacity(initialCapacity);
        }

        // Set TTL
        builder.expireAfterWrite(ttlDuration, ttlUnit);

        // Enable stats if requested
        if (recordStats) {
            builder.recordStats();
        }

        logger.info("Created Caffeine cache builder: maxSize=" + maxSize +
                ", ttl=" + ttlDuration + " " + ttlUnit +
                ", stats=" + recordStats);

        return builder;
    }

    /**
     * Convert this configuration to a Guava CacheBuilder.
     *
     * <p><b>Requires:</b> Guava library on classpath
     * <p><b>Example:</b>
     * <pre>{@code
     * CacheConfig config = CacheConfig.loadDefault();
     * LoadingCache<String, BitSet> cache = config.toGuavaCacheBuilder()
     *     .build(cacheLoader);
     * }</pre>
     *
     * @param <K> Cache key type
     * @param <V> Cache value type
     * @return Configured Guava CacheBuilder
     * @throws IllegalStateException if Guava not available on classpath
     */
    @SuppressWarnings("unchecked")
    public <K, V> com.google.common.cache.CacheBuilder<K, V> toGuavaCacheBuilder() {
        try {
            Class.forName("com.google.common.cache.CacheBuilder");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    "Guava library not found on classpath. Add dependency:\n" +
                            "<dependency>\n" +
                            "  <groupId>com.google.guava</groupId>\n" +
                            "  <artifactId>guava</artifactId>\n" +
                            "  <version>33.0.0-jre</version>\n" +
                            "</dependency>", e);
        }

        com.google.common.cache.CacheBuilder<K, V> builder =
                (com.google.common.cache.CacheBuilder<K, V>) com.google.common.cache.CacheBuilder.newBuilder();

        // Set maximum size
        builder.maximumSize(maxSize);

        // Set initial capacity if specified
        if (initialCapacity > 0) {
            builder.initialCapacity(initialCapacity);
        }

        // Set TTL
        builder.expireAfterWrite(ttlDuration, ttlUnit);

        // Enable stats if requested
        if (recordStats) {
            builder.recordStats();
        }

        logger.info("Created Guava cache builder: maxSize=" + maxSize +
                ", ttl=" + ttlDuration + " " + ttlUnit +
                ", stats=" + recordStats);

        return builder;
    }

    /**
     * Convert this configuration to Redisson client configuration.
     *
     * <p><b>Requires:</b> Redisson library on classpath
     * <p><b>Example:</b>
     * <pre>{@code
     * CacheConfig config = CacheConfig.loadDefault();
     * org.redisson.config.Config redisConfig = config.toRedissonConfig();
     * RedissonClient client = Redisson.create(redisConfig);
     * RMapCache<String, BitSet> cache = client.getMapCache("baseConditions");
     * }</pre>
     *
     * @return Configured Redisson Config
     * @throws IllegalStateException if cache type is not REDIS or Redisson not available
     */
    public org.redisson.config.Config toRedissonConfig() {
        if (cacheType != CacheType.REDIS) {
            throw new IllegalStateException(
                    "toRedissonConfig() requires cache.type=REDIS, but was: " + cacheType);
        }

        try {
            Class.forName("org.redisson.Redisson");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    "Redisson library not found on classpath. Add dependency:\n" +
                            "<dependency>\n" +
                            "  <groupId>org.redisson</groupId>\n" +
                            "  <artifactId>redisson</artifactId>\n" +
                            "  <version>3.25.0</version>\n" +
                            "</dependency>", e);
        }

        org.redisson.config.Config config = new org.redisson.config.Config();

        if (redisUseCluster) {
            // Cluster mode - parse multiple addresses
            String[] addresses = redisAddress.split(",");
            config.useClusterServers()
                    .addNodeAddress(addresses)
                    .setPassword(redisPassword != null && !redisPassword.isEmpty() ? redisPassword : null)
                    .setMasterConnectionPoolSize(redisConnectionPoolSize)
                    .setSlaveConnectionPoolSize(redisConnectionPoolSize)
                    .setMasterConnectionMinimumIdleSize(redisMinIdleSize)
                    .setSlaveConnectionMinimumIdleSize(redisMinIdleSize)
                    .setTimeout(redisTimeoutMs)
                    .setRetryAttempts(3)
                    .setRetryInterval(1500);

            logger.info("Created Redisson CLUSTER config: addresses=" + redisAddress +
                    ", poolSize=" + redisConnectionPoolSize);
        } else {
            // Single server mode
            config.useSingleServer()
                    .setAddress(redisAddress)
                    .setPassword(redisPassword != null && !redisPassword.isEmpty() ? redisPassword : null)
                    .setConnectionPoolSize(redisConnectionPoolSize)
                    .setConnectionMinimumIdleSize(redisMinIdleSize)
                    .setTimeout(redisTimeoutMs)
                    .setRetryAttempts(3)
                    .setRetryInterval(1500);

            logger.info("Created Redisson SINGLE config: address=" + redisAddress +
                    ", poolSize=" + redisConnectionPoolSize);
        }

        return config;
    }

    /**
     * Get codec configuration for Redis value serialization.
     *
     * <p>This configuration determines how cache values are serialized/compressed
     * when stored in Redis. Uses snappy compression for values exceeding the
     * compression threshold.
     *
     * @return Redis codec configuration
     */
    public org.redisson.client.codec.Codec getRedisCodec() {
        if (cacheType != CacheType.REDIS) {
            throw new IllegalStateException(
                    "getRedisCodec() requires cache.type=REDIS, but was: " + cacheType);
        }

        // Use Snappy compression codec if threshold configured
        if (redisCompressionThreshold > 0 && redisCompressionThreshold < Integer.MAX_VALUE) {
            try {
                Class.forName("org.redisson.codec.SnappyCodecV2");
                return new org.redisson.codec.SnappyCodecV2();
            } catch (ClassNotFoundException e) {
                logger.warning("Snappy codec not available, falling back to default codec");
                return new org.redisson.codec.JsonJacksonCodec();
            }
        }

        return new org.redisson.codec.JsonJacksonCodec();
    }

    /**
     * Create a complete cache instance based on this configuration.
     *
     * <p>This is a convenience method that automatically creates the appropriate
     * cache implementation based on the cache type.
     *
     * <p><b>Examples:</b>
     * <pre>{@code
     * // Caffeine cache
     * CacheConfig config = CacheConfig.loadDefault();
     * Cache<String, BitSet> cache = config.buildCache();
     *
     * // Redis cache
     * CacheConfig redisConfig = CacheConfig.forDistributed("redis://host:6379", null);
     * RMapCache<String, BitSet> cache = redisConfig.buildRedisCache("baseConditions");
     * }</pre>
     *
     * @param <K> Cache key type
     * @param <V> Cache value type
     * @return Configured cache instance
     */
    @SuppressWarnings("unchecked")
    public <K, V> com.github.benmanes.caffeine.cache.Cache<K, V> buildCache() {
        if (cacheType == CacheType.REDIS) {
            throw new IllegalStateException(
                    "buildCache() cannot create Redis caches. Use buildRedisCache(cacheName) instead.");
        }

        return toCaffeineBuilder().build();
    }

    /**
     * Create a Redis map cache with the given name.
     *
     * @param <K> Cache key type
     * @param <V> Cache value type
     * @param cacheName Name of the Redis map cache
     * @return Configured Redis map cache
     */
    public <K, V> org.redisson.api.RMapCache<K, V> buildRedisCache(String cacheName) {
        if (cacheType != CacheType.REDIS) {
            throw new IllegalStateException(
                    "buildRedisCache() requires cache.type=REDIS, but was: " + cacheType);
        }

        org.redisson.config.Config config = toRedissonConfig();
        org.redisson.api.RedissonClient client = org.redisson.Redisson.create(config);

        return client.getMapCache(cacheName);
    }

    // ========================================================================
    // TO STRING
    // ========================================================================

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("CacheConfig{");
        sb.append("cacheType=").append(cacheType);
        sb.append(", maxSize=").append(maxSize);
        sb.append(", initialCapacity=").append(initialCapacity);
        sb.append(", ttl=").append(ttlDuration).append(" ").append(ttlUnit);
        sb.append(", recordStats=").append(recordStats);

        if (cacheType == CacheType.ADAPTIVE) {
            sb.append(", adaptiveSizing=").append(enableAdaptiveSizing);
            sb.append(", sizeRange=[").append(minCacheSize).append("-").append(maxCacheSize).append("]");
            sb.append(", hitRateThresholds=[").append(lowHitRateThreshold)
                    .append("-").append(highHitRateThreshold).append("]");
            sb.append(", tuningInterval=").append(tuningIntervalSeconds).append("s");
        }

        if (cacheType == CacheType.REDIS) {
            sb.append(", redisAddress=").append(redisAddress);
            sb.append(", redisAuth=").append(redisPassword != null ? "***" : "none");
            sb.append(", poolSize=").append(redisConnectionPoolSize);
            sb.append(", timeout=").append(redisTimeoutMs).append("ms");
            sb.append(", compression=").append(redisCompressionThreshold).append("bytes");
            sb.append(", cluster=").append(redisUseCluster);
        }

        sb.append('}');
        return sb.toString();
    }
}