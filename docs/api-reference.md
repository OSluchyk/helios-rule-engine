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
The core interface for caching base condition evaluation results. Implementations must be thread-safe.

```java
public interface BaseConditionCache {
    CompletableFuture<BitSet> get(CacheKey key);
    void put(CacheKey key, BitSet value);
    void invalidateAll();
    Map<String, Object> getMetrics();
}
```

### `CacheKey`
Immutable key used for cache lookups. Composed of:
*   `baseConditionId`: Unique ID of the base condition set.
*   `eventHash`: Hash of the event attributes relevant to the condition.

### `CacheConfig`
Unified configuration object for all cache types.

#### Cache Types (`CacheType`)
*   `IN_MEMORY`: Simple ConcurrentHashMap-based cache (Development).
*   `CAFFEINE`: High-performance local cache with W-TinyLFU eviction (Production).
*   `ADAPTIVE`: Self-tuning Caffeine cache that adjusts size based on hit rates (Auto-scale).
*   `REDIS`: Distributed cache using Redis (Clustered/Distributed).
*   `NO_OP`: Disables caching.

#### Common Properties
*   `maxSize`: Maximum number of entries (default: 100,000).
*   `ttl`: Time-to-live duration (default: 10 minutes).
*   `recordStats`: Enable metric collection (default: true).

#### Redis Properties
*   `redisAddress`: Connection string (e.g., `redis://localhost:6379`).
*   `redisPoolSize`: Connection pool size (default: 64).
*   `redisUseCluster`: Enable Redis Cluster support (default: false).

#### Adaptive Properties
*   `enableAdaptiveSizing`: Enable auto-sizing (default: false).
*   `minCacheSize` / `maxCacheSize`: Sizing bounds.
*   `lowHitRateThreshold` / `highHitRateThreshold`: Tuning triggers.

### `CacheFactory`
Factory for creating cache instances.

```java
// Create from config
CacheConfig config = CacheConfig.forProduction();
BaseConditionCache cache = CacheFactory.create(config);
```

### Implementations
*   **`InMemoryBaseConditionCache`**: Basic thread-safe map.
*   **`CaffeineBaseConditionCache`**: Production-grade local cache.
*   **`AdaptiveCaffeineCache`**: Smart local cache that resizes dynamically.
*   **`RedisBaseConditionCache`**: Distributed cache implementation.
*   **`NoOpCache`**: Passthrough implementation (always misses).
