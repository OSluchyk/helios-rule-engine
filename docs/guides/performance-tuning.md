# Performance Tuning Guide

## Cache Configuration

Helios provides multiple cache implementations optimized for different deployment scenarios.

### Recommended: CacheConfig and CacheFactory

```java
// Production single-node (Caffeine)
CacheConfig config = CacheConfig.forProduction();
BaseConditionCache cache = CacheFactory.create(config);

// Production auto-scale (Adaptive Caffeine)
CacheConfig config = CacheConfig.forAdaptiveProduction();
BaseConditionCache cache = CacheFactory.create(config);

// Production multi-instance (Redis)
CacheConfig config = CacheConfig.forDistributed(
    "redis://redis-cluster:6379",
    System.getenv("REDIS_PASSWORD")
);
BaseConditionCache cache = CacheFactory.create(config);
```

### Environment Variable Configuration

```bash
export CACHE_TYPE=ADAPTIVE
export CACHE_MAX_SIZE=100000
export CACHE_TTL_MINUTES=10
export CACHE_RECORD_STATS=true
export CACHE_ENABLE_ADAPTIVE_SIZING=true
```

Then load in code:
```java
CacheConfig config = CacheConfig.fromEnvironment();
BaseConditionCache cache = CacheFactory.create(config);
```

**Guidelines:**
- **Size:** Start with ~1000 entries per rule.
- **Hit Rate:** Target >90% (Caffeine/Adaptive), >70% (Redis).

## JVM Optimization (Java 25)

For maximum performance, use the following JVM flags:

```bash
java -Xms8g -Xmx8g \
     -XX:+UseZGC \
     -XX:+ZGenerational \
     -XX:+UseCompactObjectHeaders \
     -XX:+EnableVectorAPI \
     -XX:+AlwaysPreTouch \
     -jar rule-engine.jar
```

- **ZGC:** Sub-5ms pause times.
- **Compact Headers:** Reduces memory footprint by ~40%.
- **Vector API:** Accelerates numeric predicate evaluation.

## Benchmarking

Run the built-in benchmarks to verify performance on your hardware:

```bash
mvn test-compile exec:java \
  -Dexec.mainClass="com.helios.ruleengine.benchmark.GranularCliffBenchmark" \
  -Dexec.classpathScope=test
```
