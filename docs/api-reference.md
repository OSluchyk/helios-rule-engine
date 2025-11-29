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

### `CacheConfig`
Configuration object for the cache.
*   `type`: `IN_MEMORY`, `REDIS`, `ADAPTIVE`, `NO_OP`
*   `maxSize`: Maximum number of entries (for in-memory)
*   `redisAddress`: Redis connection string
*   `redisPoolSize`: Connection pool size

### `CacheFactory`
Factory for creating cache instances based on `CacheConfig`.

### Implementations
*   **`InMemoryBaseConditionCache`**: High-performance, ConcurrentHashMap-based cache.
*   **`RedisBaseConditionCache`**: Distributed cache using Redis (supports clustering).
*   **`AdaptiveCaffeineCache`**: Self-tuning in-memory cache using Caffeine's Window TinyLfu policy.
*   **`NoOpCache`**: Disables caching.
