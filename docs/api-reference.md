# API Reference

## Core Classes

### `RuleCompiler`
Compiles JSON rules into an optimized `EngineModel`.

```java
RuleCompiler compiler = new RuleCompiler(tracer);
EngineModel model = compiler.compile(Path.of("rules.json"));
```

### `RuleEvaluator`
Evaluates events against the compiled model. Thread-safe and optimized for concurrency.

```java
RuleEvaluator evaluator = new RuleEvaluator(model);
MatchResult result = evaluator.evaluate(event);
```

### `Event`
Immutable representation of a business event.

```java
Event event = new Event(
    String eventId,
    String eventType,
    Map<String, Object> attributes
);
```

### `MatchResult`
Contains the outcome of an evaluation.

```java
record MatchResult(
    String eventId,
    List<MatchedRule> matchedRules,
    long evaluationTimeNanos
)
```

### `EngineModelManager`
Manages the lifecycle of the `EngineModel`, including hot reloading.

```java
EngineModelManager manager = new EngineModelManager(path, tracer);
manager.start();
EngineModel currentModel = manager.getEngineModel();
```

## Caching API

### `BaseConditionCache`
The core interface for caching base condition evaluation results.

```java
public interface BaseConditionCache {
    RoaringBitmap get(CacheKey key);
    void put(CacheKey key, RoaringBitmap value);
    void invalidate(CacheKey key);
    void clear();
    CacheStats getStats();
}
```

### `CacheConfig`
Unified configuration object for all cache implementations.

**Cache Types:**
*   `IN_MEMORY` - Simple LRU cache for development/testing
*   `CAFFEINE` - High-performance W-TinyLFU cache for production single-node
*   `ADAPTIVE` - Self-tuning Caffeine cache for auto-scaling environments
*   `REDIS` - Distributed cache for multi-instance deployments
*   `NO_OP` - Disabled cache for testing/benchmarking

**Common Properties:**
*   `maxSize`: Maximum number of entries
*   `initialCapacity`: Initial capacity hint
*   `ttlDuration` / `ttlUnit`: Time-to-live for cache entries
*   `recordStats`: Enable statistics collection

**Redis Properties:**
*   `redisAddress`: Redis connection string (e.g., "redis://host:6379")
*   `redisPassword`: Authentication password
*   `redisConnectionPoolSize`: Connection pool size
*   `redisMinIdleSize`: Minimum idle connections
*   `redisTimeoutMs`: Operation timeout
*   `redisCompressionThreshold`: Compress values larger than this (bytes)
*   `redisUseCluster`: Enable Redis cluster mode

**Adaptive Properties:**
*   `enableAdaptiveSizing`: Enable auto-sizing
*   `minCacheSize` / `maxCacheSize`: Size adjustment range
*   `lowHitRateThreshold` / `highHitRateThreshold`: Hit rate thresholds
*   `tuningIntervalSeconds`: How often to adjust size

**Factory Methods:**
```java
CacheConfig.forDevelopment()           // IN_MEMORY, 10K entries
CacheConfig.forProduction()            // CAFFEINE, 100K entries
CacheConfig.forAdaptiveProduction()    // ADAPTIVE, auto-sizing
CacheConfig.forDistributed(addr, pwd)  // REDIS
CacheConfig.fromEnvironment()          // Load from env vars
CacheConfig.loadDefault()              // Load from cache.properties
```

### `CacheFactory`
Factory for creating cache instances based on `CacheConfig`.

```java
BaseConditionCache cache = CacheFactory.create(config);
BaseConditionCache cache = CacheFactory.createFromEnvironment();
BaseConditionCache cache = CacheFactory.createForEnvironment("production");
```

### `CacheKey`
Immutable cache key for base condition lookups.

```java
public record CacheKey(
    int baseConditionSetId,
    long eventAttributesHash
) {}
```

### Cache Implementations

*   **`InMemoryBaseConditionCache`**: Simple ConcurrentHashMap-based LRU cache for development.
*   **`CaffeineBaseConditionCache`**: High-performance W-TinyLFU cache using Caffeine library.
*   **`AdaptiveCaffeineCache`**: Self-tuning Caffeine cache that automatically adjusts size based on hit rates.
*   **`RedisBaseConditionCache`**: Distributed cache using Redis/Redisson (supports clustering and compression).
*   **`OptimizedBaseConditionCache`**: Performance-optimized cache with reduced overhead.
*   **`NoOpCache`**: Null-object pattern cache that disables caching entirely.
