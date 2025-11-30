# Cache Operations Runbook

## Monitoring & Alerts

### Key Metrics to Track

1. **Cache Metrics**
   - **Hit Rate**: Target >90% (Caffeine/Adaptive), >70% (Redis). Low hit rate implies inefficient caching or highly variable traffic.
   - **Eviction Rate**: Should be low. High eviction rate indicates the cache is too small.
   - **Latency**: Average get/put latency. Target <100ns (IN_MEMORY/CAFFEINE), <500ns (ADAPTIVE), <1ms (REDIS).

2. **Application Metrics**
   - **Predicates Evaluated per Event**: Target < 1000.
   - **Base Condition Sets Count**: Number of unique condition combinations.
   - **P99 Evaluation Latency**: Target < 1ms.

3. **Redis Metrics** (if applicable)
   - **Memory Usage**: Ensure sufficient headroom.
   - **Network Latency**: Should be sub-millisecond.
   - **Connection Pool Utilization**: Avoid exhaustion.

### Alert Thresholds

```yaml
alerts:
  - name: cache_hit_rate_low
    condition: cache.hit_rate < 0.90
    severity: warning
    description: "Cache hit rate dropped below 90%. Check for traffic pattern changes."
    applies_to: [CAFFEINE, ADAPTIVE, IN_MEMORY]

  - name: cache_hit_rate_low_redis
    condition: cache.hit_rate < 0.70
    severity: warning
    description: "Redis cache hit rate dropped below 70%. Check network latency or cache sizing."
    applies_to: [REDIS]

  - name: evaluation_latency_high
    condition: p99_latency > 1ms
    severity: critical
    description: "P99 latency exceeding SLA (<1ms). Investigate cache performance."

  - name: redis_memory_high
    condition: redis.memory_used > 80%
    severity: warning
    description: "Redis memory usage high. Consider scaling or tuning eviction."
    applies_to: [REDIS]

  - name: adaptive_cache_not_tuning
    condition: cache.last_tuning_age > 120s
    severity: warning
    description: "Adaptive cache has not tuned in over 2 minutes. Check tuning scheduler."
    applies_to: [ADAPTIVE]
```

## Troubleshooting Guide

### Issue: Low Cache Hit Rate
**Symptoms:** Hit rate < 90%, high predicate evaluation count, increased latency.

**Solutions:**
1. **Increase Cache Size:** If evictions are high, the working set doesn't fit.
2. **Extend TTL:** If data is stable, increase TTL to reduce cold misses.
3. **Review Base Conditions:** Ensure the `BaseConditionEvaluator` is selecting effective conditions.
4. **Analyze Traffic:** Check for highly unique event attributes (high cardinality) that might be polluting the cache keys.

### Issue: Memory Growth
**Symptoms:** Increasing memory usage over time (In-Memory Cache).

**Solutions:**
1. **Reduce Max Size:** Enforce a stricter limit on the cache.
2. **Decrease TTL:** Expire stale entries faster.
3. **Enable Compression:** If using Redis or custom serialization, enable compression for large BitSets.
4. **Review Eviction Policy:** Ensure LRU/LFU is functioning correctly.

### Issue: Redis Connection Issues
**Symptoms:** Timeouts, connection pool exhaustion exceptions.

**Solutions:**
1. **Increase Pool Size:** If utilization is consistently 100%.
2. **Add Replicas:** Distribute read load.
3. **Circuit Breaker:** Ensure the application fails gracefully (falls back to no-cache or in-memory) if Redis is down.
4. **Multiplexing:** Use connection multiplexing if supported by the client.
