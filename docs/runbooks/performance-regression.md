# Performance Regression Runbook

## Overview

This runbook guides you through investigating and resolving performance regressions in the Helios Rule Engine. Use this when benchmarks show degraded throughput, increased latency, or unexpected memory growth.

---

## Quick Diagnosis Checklist

Run these checks first to identify the regression type:

- [ ] **Compare benchmark results** - Is throughput down or latency up?
- [ ] **Check profiler data** - Which methods consume the most CPU/memory?
- [ ] **Review recent changes** - What code changed since last good benchmark?
- [ ] **Validate environment** - Same JVM flags, hardware, and workload?

---

## Step 1: Run Benchmarks

### Current Performance Baseline

```bash
# Run standard 2-minute benchmark
mvn clean test-compile exec:java \
  -Dexec.mainClass="com.helios.ruleengine.benchmark.SimpleBenchmark" \
  -Dexec.classpathScope=test

# Quick 1-minute check for rapid iteration
mvn test-compile exec:java \
  -Dexec.mainClass="com.helios.ruleengine.benchmark.SimpleBenchmark" \
  -Dexec.classpathScope=test \
  -Dbench.quick=true
```

### Expected Performance Targets

| Metric | 500 Rules (HOT) | 2000 Rules (HOT) | 5000 Rules (HOT) |
|--------|-----------------|------------------|------------------|
| **Throughput** | >120 ops/s | >29 ops/s | >13 ops/s |
| **P50 Latency** | <75 µs | <275 µs | <650 µs |
| **P99 Latency** | <170 µs | <830 µs | <2.1 ms |

**Alert if:**
- Throughput drops >20% from baseline
- P99 latency increases >30% from baseline
- Memory usage grows >50% from baseline

---

## Step 2: Profile the Hot Path

### Run with JFR (Java Flight Recorder)

```bash
# Profile during benchmark with JFR
java -XX:StartFlightRecording=filename=profile.jfr,settings=profile \
     -Xms8g -Xmx8g \
     -XX:+UseZGC -XX:+ZGenerational \
     -XX:+UseCompactObjectHeaders \
     --add-modules=jdk.incubator.vector \
     -jar helios-benchmarks/target/benchmarks.jar SimpleBenchmark

# Analyze with JMC (Java Mission Control)
jmc profile.jfr
```

### Key Profiling Metrics to Check

#### CPU Profiling (Method View)

**Expected distribution:**
- `updateCountersOptimized`: 20-30% (adaptive intersection strategy)
- `RoaringBitmap.and/contains`: <15% (should NOT dominate)
- `evaluatePredicatesHybrid`: 15-20%
- `BaseConditionEvaluator.evaluate`: 10-15%

**🚨 RED FLAGS:**
- `hybridUnsignedBinarySearch` >40% → RoaringBitmap contains() bottleneck
- `ArrayContainer.clone` >10% → Excessive bitmap cloning
- Lambda allocations >5% → Missing cached consumers

#### Memory Profiling (Allocation View)

**Expected allocations:**
- `RoaringArray`: <500 MiB (with pooling)
- Lambda instances: <50 MiB (with cached consumers)
- `int[]` arrays: <400 MiB
- Total heap: <4 GiB for 5000 rules

**🚨 RED FLAGS:**
- `RoaringArray` >1 GiB → Bitmap cloning leak
- Lambda instances >200 MiB → Missing consumer pooling
- Rapid GC cycles → Memory leak or over-allocation

---

## Step 3: Common Performance Issues & Fixes

### Issue #1: RoaringBitmap Contains() Bottleneck

**Symptoms:**
- `hybridUnsignedBinarySearch` consumes >40% CPU
- Throughput degrades non-linearly with rule count
- Stack trace shows `RoaringBitmap.contains()` → `updateCountersOptimized`

**Root Cause:**
- Using `contains()` check in loop: O(n * log m) complexity
- Large posting lists (>128 rules) make contains() expensive

**Fix:**
Check `RuleEvaluator.updateCountersOptimized()` uses adaptive strategy:

```java
if (cardinality < INTERSECTION_CARDINALITY_THRESHOLD) {
    // Small: use contains()
} else {
    // Large: use intersection()
    RoaringBitmap.and(affectedRules, eligibleRulesRoaring, intersectionBuffer);
}
```

**Verification:**
- Re-run benchmarks: expect 2-3x throughput improvement
- Profile: `hybridUnsignedBinarySearch` should drop to <15% CPU

---

### Issue #2: Excessive RoaringBitmap Allocations

**Symptoms:**
- Memory profiler shows `RoaringArray` >1 GiB
- Frequent GC pauses
- Throughput degrades over time

**Root Cause:**
- Missing bitmap pooling
- Unnecessary `clone()` calls on read path

**Fix:**

1. Check `INTERSECTION_BUFFER` ThreadLocal exists:
```java
private static final ThreadLocal<RoaringBitmap> INTERSECTION_BUFFER =
    ThreadLocal.withInitial(RoaringBitmap::new);
```

2. Verify bitmap reuse:
```java
RoaringBitmap buffer = INTERSECTION_BUFFER.get();
buffer.clear(); // ← Must clear before reuse
RoaringBitmap.and(a, b, buffer); // ← Reuse buffer, no allocation
```

3. Audit all `.clone()` calls:
```bash
grep -n "\.clone()" helios-evaluator/src/main/java/**/*.java
# Each clone should have a comment justifying why it's needed
```

**Verification:**
- `RoaringArray` allocations should drop <500 MiB
- GC pauses should decrease

---

### Issue #3: Lambda Allocation Storm

**Symptoms:**
- Memory profiler shows >200 MiB in lambda instances
- Allocation rate high despite "zero-allocation" design

**Root Cause:**
- Missing cached `IntConsumer` for RoaringBitmap iteration
- JIT may not eliminate lambda allocations under load

**Fix:**

Replace lambdas with cached consumers in hot paths:

```java
// BAD - Lambda allocation in hot path
affectedRules.forEach((int ruleId) -> {
    counters[ruleId]++;
});

// GOOD - Cached consumer
private final ThreadLocal<CounterUpdater> updaterPool =
    ThreadLocal.withInitial(CounterUpdater::new);

CounterUpdater updater = updaterPool.get();
updater.configure(counters, touchedRules);
affectedRules.forEach(updater); // ← No allocation
```

**Verification:**
- Lambda allocations should drop <50 MiB
- Check with: `jcmd <pid> GC.heap_info`

---

### Issue #4: Cache Hit Rate Below Target

**Symptoms:**
- Benchmark shows COLD/WARM scenarios similar performance to HOT
- No significant speedup after warmup phase

**Root Cause:**
- Cache size too small for working set
- Cache key generation not deterministic
- Cache eviction too aggressive

**Fix:**

1. Check cache hit rate metrics:
```java
Map<String, Object> metrics = evaluator.getDetailedMetrics();
double hitRate = (double) metrics.get("cacheHitRate");
// Should be >0.90 for HOT scenario
```

2. Increase cache size if needed:
```bash
export CACHE_MAX_SIZE=200000  # 2x default
export CACHE_TTL_MINUTES=15   # Increase TTL
```

3. Verify cache key stability:
```java
// Cache keys must be deterministic for same event attributes
CacheKey key1 = generateCacheKey(attrs, sets);
CacheKey key2 = generateCacheKey(attrs, sets);
assert key1.equals(key2); // Must be true!
```

**Verification:**
- HOT scenario should show 2-3x throughput vs COLD
- Cache hit rate >90% after warmup

---

### Issue #5: Non-Linear Scalability

**Symptoms:**
- Performance degrades worse than O(n) with rule count
- 500→2000 rules (4x) causes >4x latency increase

**Root Cause:**
- Algorithm changed from O(n) to O(n²) or worse
- Missing early-exit optimizations
- Quadratic loops or nested iterations

**Diagnostic:**

Plot latency vs rule count on log-log scale:
```
If slope > 1.0 → worse than linear scaling
If slope > 1.5 → quadratic or worse
```

**Common culprits:**
- Nested rule iterations without break conditions
- Recomputing expensive predicates per rule
- Missing bloom filters or early pruning

**Fix:** Review recent algorithm changes, look for nested loops.

---

## Step 4: Validate JVM Configuration

### Required JVM Flags

```bash
-XX:+UseZGC                      # Low-latency GC
-XX:+ZGenerational               # Generational mode (Java 21+)
-XX:+UseCompactObjectHeaders     # 40% memory reduction
--add-modules=jdk.incubator.vector  # SIMD support
-Xms8g -Xmx8g                    # Fixed heap (prevents resizing)
-XX:+AlwaysPreTouch              # Touch memory upfront
-XX:MaxInlineLevel=15            # Aggressive inlining
-XX:InlineSmallCode=2000         # Inline larger methods
```

### Verify Active Flags

```bash
java -XX:+PrintFlagsFinal -version | grep -E "UseZGC|CompactObjectHeaders|InlineSmallCode"
```

**Expected output:**
```
bool UseZGC = true
bool UseCompactObjectHeaders = true
intx InlineSmallCode = 2000
```

---

## Step 5: Compare with Known Good Build

### Bisect Performance Regression

If you know the last good commit:

```bash
git bisect start
git bisect bad HEAD           # Current (bad) performance
git bisect good <last-good-commit>

# Git will checkout middle commit
mvn clean package -DskipTests
# Run benchmark, then:
git bisect good   # if performance is good
git bisect bad    # if performance is bad

# Repeat until culprit commit is found
```

### Diff Key Performance Files

```bash
# Compare critical files with last good version
git diff <good-commit> HEAD -- \
  helios-evaluator/src/main/java/com/helios/ruleengine/runtime/evaluation/RuleEvaluator.java \
  helios-evaluator/src/main/java/com/helios/ruleengine/runtime/evaluation/BaseConditionEvaluator.java
```

---

## Step 6: Continuous Monitoring Setup

### Add Performance Tests to CI/CD

```yaml
# .github/workflows/performance.yml
name: Performance Benchmarks
on: [pull_request]

jobs:
  benchmark:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-java@v3
        with:
          java-version: '21'
      - name: Run benchmarks
        run: mvn test-compile exec:java -Dexec.mainClass="..." -Dbench.quick=true
      - name: Compare with baseline
        run: |
          # Fail if throughput drops >20% or latency increases >30%
          python scripts/compare_benchmarks.py results.json baseline.json --threshold 0.20
```

### Alerts to Configure

- **Throughput drop >20%**: Page on-call
- **P99 latency >2ms** (5000 rules): Warning
- **Memory >6GB**: Investigate
- **GC pauses >10ms**: Warning

---

## Prevention Strategies

### 1. Mandatory Benchmarking Before Merge

- Run `SimpleBenchmark` on every PR
- Compare with main branch baseline
- Require sign-off if performance degrades >10%

### 2. Profiling-Aware Code Reviews

- **Hot path changes** must include profiling results
- Add `// CRITICAL PATH` comments to methods that consume >10% CPU
- Document optimization trade-offs in code

### 3. Performance Budget

Set and enforce:
- Max latency: P99 <1ms @ 5000 rules
- Min throughput: >120 ops/s @ 500 rules
- Max memory: <6GB @ 100K rules
- Max allocation rate: <100 MB/s steady state

### 4. Regression Test Suite

Add microbenchmarks for critical paths:
```java
@Benchmark
public void benchmarkUpdateCounters(Blackhole bh) {
    // Test just updateCountersOptimized() in isolation
}
```

---

## Emergency Rollback Procedure

If production performance degrades:

1. **Immediate**: Roll back to last known good version
2. **Verify**: Confirm rollback restores performance
3. **Investigate**: Use this runbook to diagnose root cause
4. **Fix**: Apply fix to development branch
5. **Validate**: Re-run full benchmark suite
6. **Deploy**: Gradual rollout with monitoring

---

## Useful Commands Reference

```bash
# Quick benchmark (1 minute)
mvn test-compile exec:java -Dexec.mainClass="..." -Dbench.quick=true

# Profile with JFR
java -XX:StartFlightRecording=filename=profile.jfr ...

# Check GC activity
jstat -gcutil <pid> 1000  # Every 1 second

# Heap dump on OOM
-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp/heap.hprof

# Print inlining decisions
-XX:+PrintInlining -XX:+UnlockDiagnosticVMOptions

# Check allocation rate
jcmd <pid> GC.heap_info
```

---

## Escalation

If this runbook doesn't resolve the issue:

1. Capture full diagnostic bundle:
   - JFR profile (5+ minutes)
   - Heap dump
   - Benchmark results (before/after)
   - Git diff of suspicious changes

2. Share in #performance-eng channel with:
   - Summary of symptoms
   - Troubleshooting steps tried
   - Profiling data showing bottleneck

3. Tag performance SMEs: @perf-team

---

## Document History

- **2025-12-24**: Initial version covering RoaringBitmap bottleneck, lambda allocations, cache tuning
- **Future**: Add sections on NUMA tuning, Vector API optimization, distributed cache issues
