# Performance Tuning Guide

## Cache Configuration

Helios uses Caffeine for caching base condition evaluation results.

```java
BaseConditionCache cache = new CaffeineBaseConditionCache.builder()
    .maxSize(100_000)                           // Adjust based on memory
    .expireAfterWrite(10, TimeUnit.MINUTES)     // TTL
    .recordStats(true)                          // Enable monitoring
    .build();
```

**Guidelines:**
- **Size:** Start with ~1000 entries per rule.
- **Hit Rate:** Target >70%.

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
