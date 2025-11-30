# Performance Tuning Guide

## Cache Configuration

Helios supports multiple caching strategies configured via `CacheConfig` and `CacheFactory`.

### Recommended Configuration Pattern

```java
CacheConfig config = CacheConfig.builder()
    .cacheType(CacheConfig.CacheType.CAFFEINE) // or ADAPTIVE, REDIS
    .maxSize(100_000)
    .ttl(10, TimeUnit.MINUTES)
    .recordStats(true)
    .build();

BaseConditionCache cache = CacheFactory.create(config);
```

### Environment Variable Configuration

You can tune the cache without code changes using environment variables:

```bash
# Production Tuning
export CACHE_TYPE=CAFFEINE
export CACHE_MAX_SIZE=200000
export CACHE_TTL_MINUTES=15
```

### Performance Targets

| Cache Type | Target Hit Rate | Notes |
|------------|-----------------|-------|
| **Caffeine** | >90% | High hit rate expected for single-node. |
| **Adaptive** | >90% | Should maintain high hit rates even under load. |
| **Redis** | >70% | Lower hit rate acceptable due to network latency. |

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
