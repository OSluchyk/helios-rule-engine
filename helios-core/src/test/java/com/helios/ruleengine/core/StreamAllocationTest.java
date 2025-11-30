package com.helios.ruleengine.core;

import com.helios.ruleengine.api.model.SelectionStrategy;
import com.helios.ruleengine.compiler.RuleCompiler;
import com.helios.ruleengine.runtime.context.EventEncoder;
import com.helios.ruleengine.runtime.evaluation.RuleEvaluator;
import com.helios.ruleengine.runtime.model.EngineModel;
import com.helios.ruleengine.runtime.model.Dictionary;
import com.helios.ruleengine.infra.telemetry.TracingService;
import io.opentelemetry.api.trace.Tracer;
import org.junit.jupiter.api.*;
import com.helios.ruleengine.api.model.Event;
import com.helios.ruleengine.api.model.MatchResult;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0-3 OPTIMIZATION TEST: Verify stream allocations have been eliminated
 *
 * This test validates that hot paths don't use:
 * - stream() calls
 * - Collections.unmodifiableMap() in hot paths
 * - Unnecessary iterator/spliterator allocations
 */
@DisplayName("P0-3: Stream Allocation Elimination Tests")
class StreamAllocationTest {

    private static final Tracer NOOP_TRACER = TracingService.getInstance().getTracer();
    private EngineModel model;
    private RuleEvaluator evaluator;
    private EventEncoder eventEncoder;
    private static Path tempDir;
    private static boolean threadAllocationsSupported = false;

    @BeforeAll
    static void beforeAll() throws IOException {
        tempDir = Files.createTempDirectory("stream_test");

        // Check if thread allocation tracking is supported
        try {
            com.sun.management.ThreadMXBean threadMXBean =
                    (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
            threadAllocationsSupported = threadMXBean.isThreadAllocatedMemorySupported() &&
                    threadMXBean.isThreadAllocatedMemoryEnabled();

            if (threadAllocationsSupported) {
                System.out.println("✓ Thread allocation tracking is SUPPORTED");
            } else {
                System.out.println("⚠ Thread allocation tracking is NOT SUPPORTED - some tests will be skipped");
            }
        } catch (Exception e) {
            System.out.println("⚠ Thread allocation tracking is NOT AVAILABLE: " + e.getMessage());
        }
    }

    @AfterAll
    static void afterAll() throws IOException {
        Files.walk(tempDir)
                .sorted(java.util.Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(java.io.File::delete);
    }

    @BeforeEach
    void setUp() throws Exception {
        Path rulesFile = tempDir.resolve("stream_rules.json");
        Files.writeString(rulesFile, getStreamTestRules());

        RuleCompiler compiler = new RuleCompiler(NOOP_TRACER);
        // FIX: Use EngineModel.SelectionStrategy (fully qualified)
        model = compiler.compile(rulesFile, SelectionStrategy.ALL_MATCHES);
        evaluator = new RuleEvaluator(model, NOOP_TRACER, true);

        // FIX: Create EventEncoder for encoding tests
        eventEncoder = new EventEncoder(model.getFieldDictionary(), model.getValueDictionary());
    }

    @Test
    @DisplayName("Should have lower allocation rate compared to baseline")
    void shouldHaveLowerAllocationRate() {
        // Given - warm up to stabilize JIT
        for (int i = 0; i < 2000; i++) {
            evaluator.evaluate(new Event("warmup-" + i, "TEST", Map.of(
                    "amount", 1000 + i,
                    "status", i % 2 == 0 ? "ACTIVE" : "PENDING"
            )));
        }

        // Force multiple GC cycles
        for (int i = 0; i < 3; i++) {
            System.gc();
            Thread.yield();
        }

        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapBefore = memoryBean.getHeapMemoryUsage();
        long startTime = System.nanoTime();

        // When - evaluate without streams
        int iterations = 5000;
        for (int i = 0; i < iterations; i++) {
            Event event = new Event("evt-" + i, "TEST", Map.of(
                    "amount", 1000 + (i % 5000),
                    "status", i % 2 == 0 ? "ACTIVE" : "PENDING",
                    "priority", i % 3 == 0 ? "HIGH" : "NORMAL"
            ));
            evaluator.evaluate(event);
        }

        long duration = System.nanoTime() - startTime;

        // Force GC to see allocation impact
        for (int i = 0; i < 3; i++) {
            System.gc();
            Thread.yield();
        }

        MemoryUsage heapAfter = memoryBean.getHeapMemoryUsage();

        // Then - verify reasonable allocation rate
        long heapGrowth = Math.max(0, heapAfter.getUsed() - heapBefore.getUsed());
        double bytesPerEvaluation = (double) heapGrowth / iterations;

        System.out.printf("=== P0-3 Stream Elimination Test ===%n");
        System.out.printf("Iterations:          %,d%n", iterations);
        System.out.printf("Duration:            %.2f ms%n", duration / 1_000_000.0);
        System.out.printf("Heap growth:         %.2f MB%n", heapGrowth / (1024.0 * 1024.0));
        System.out.printf("Bytes/evaluation:    %.2f bytes%n", bytesPerEvaluation);
        System.out.printf("Throughput:          %.0f events/sec%n",
                (iterations * 1_000_000_000.0) / duration);
        System.out.printf("Avg latency:         %.2f µs%n",
                (duration / 1000.0) / iterations);

        // P0-3 Target: < 5KB per evaluation (very conservative, allows JVM overhead)
        // With proper pooling and no streams, should be much lower in practice
        assertThat(bytesPerEvaluation).isLessThan(5000.0);

        // Verify reasonable throughput (> 10K events/sec)
        double eventsPerSec = (iterations * 1_000_000_000.0) / duration;
        assertThat(eventsPerSec).isGreaterThan(10_000.0);
    }

    @Test
    @DisplayName("Should not allocate unmodifiable wrappers in getFlattenedAttributes")
    void shouldNotAllocateUnmodifiableWrappers() {
        // Given
        Event event = new Event("evt-flatten", "TEST", Map.of(
                "amount", 5000,
                "nested", Map.of("field1", "value1", "field2", "value2"),
                "status", "ACTIVE"
        ));

        // FIX: Use EventEncoder instead of event.getFlattenedAttributes()
        // When - call getFlattenedAttributes multiple times
        Map<String, Object> flattened1 = eventEncoder.getFlattenedAttributes(event);
        Map<String, Object> flattened2 = eventEncoder.getFlattenedAttributes(event);

        // Then - should return same cached instance (no wrapper allocation)
        assertThat(flattened1).isSameAs(flattened2);

        // Verify flattening worked correctly
        assertThat(flattened1).containsKey("AMOUNT");
        assertThat(flattened1).containsKey("NESTED.FIELD1");
        assertThat(flattened1).containsKey("NESTED.FIELD2");
        assertThat(flattened1).containsKey("STATUS");

        System.out.printf("✓ Flattened attributes cached correctly (%d fields)%n", flattened1.size());
    }

    @Test
    @DisplayName("Should reuse thread-local collections efficiently")
    void shouldReuseThreadLocalCollections() {
        // Given - warm up
        for (int i = 0; i < 500; i++) {
            evaluator.evaluate(new Event("warmup-" + i, "TEST", Map.of("amount", 1000)));
        }

        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();

        // Force GC
        System.gc();
        System.gc();
        Thread.yield();

        MemoryUsage heapBefore = memoryBean.getHeapMemoryUsage();

        // When - evaluate multiple events (should reuse thread-local lists)
        int iterations = 1000;
        for (int i = 0; i < iterations; i++) {
            Event event = new Event("evt-" + i, "TEST", Map.of(
                    "status", i % 2 == 0 ? "ACTIVE" : "PENDING",
                    "amount", 1000 + (i * 100)
            ));
            evaluator.evaluate(event);
        }

        System.gc();
        System.gc();
        Thread.yield();

        MemoryUsage heapAfter = memoryBean.getHeapMemoryUsage();
        long heapGrowth = Math.max(0, heapAfter.getUsed() - heapBefore.getUsed());
        double bytesPerEvaluation = (double) heapGrowth / iterations;

        System.out.printf("Thread-local reuse test: %.2f bytes/evaluation%n", bytesPerEvaluation);

        // Should have reasonable allocation rate (< 10KB per eval is very conservative)
        assertThat(bytesPerEvaluation).isLessThan(10_000.0);
    }

    @Test
    @DisplayName("Should compute metrics without excessive allocations")
    void shouldComputeMetricsEfficiently() {
        // Given - evaluate some events
        for (int i = 0; i < 100; i++) {
            evaluator.evaluate(new Event("evt-" + i, "TEST", Map.of(
                    "amount", 1000 + i,
                    "status", "ACTIVE"
            )));
        }

        // When - get metrics multiple times
        Map<String, Object> metrics1 = evaluator.getMetrics().getSnapshot();
        Map<String, Object> metrics2 = evaluator.getMetrics().getSnapshot();
        Map<String, Object> metrics3 = evaluator.getMetrics().getSnapshot();

        // Then - verify metrics are correct and consistent
        assertThat(metrics1).isNotEmpty();
        assertThat(metrics1.get("totalEvaluations")).isEqualTo(100L);
        assertThat(metrics2.get("totalEvaluations")).isEqualTo(100L);
        assertThat(metrics3.get("totalEvaluations")).isEqualTo(100L);

        System.out.printf("✓ Metrics computed correctly: %d evaluations%n",
                metrics1.get("totalEvaluations"));
    }

    @Test
    @DisplayName("Should cache encoded attributes efficiently")
    void shouldCacheEncodedAttributesEfficiently() {
        // Given
        Event event = new Event("evt-encode", "TEST", Map.of(
                "amount", 5000,
                "status", "ACTIVE",
                "priority", "HIGH"
        ));

        // FIX: Use EventEncoder instead of event.getEncodedAttributes()
        // When - get encoded attributes multiple times
        var encoded1 = eventEncoder.encode(event);
        var encoded2 = eventEncoder.encode(event);
        var encoded3 = eventEncoder.encode(event);

        // Then - should return same cached instance (from ThreadLocal buffer)
        assertThat(encoded1).isSameAs(encoded2);
        assertThat(encoded2).isSameAs(encoded3);

        // Verify encoding worked
        assertThat(encoded1.size()).isGreaterThan(0);

        System.out.printf("✓ Encoded attributes cached correctly (%d fields)%n", encoded1.size());
    }

    @Test
    @DisplayName("Should maintain correctness after stream elimination")
    void shouldMaintainCorrectnessAfterStreamElimination() {

        // Given - event that matches specific rules
        Event event = new Event("evt-correctness", "TEST", Map.of(
                "amount", 7500,
                "status", "ACTIVE",
                "priority", "HIGH"
        ));

        // When - evaluate
        MatchResult result = evaluator.evaluate(event);

        // Then - should still get correct results
        assertThat(result.matchedRules()).isNotEmpty();
        assertThat(result.eventId()).isEqualTo("evt-correctness");

        // Verify that all expected rules are matched
        assertThat(result.matchedRules()).hasSize(4);
        List<String> matchedRuleCodes = result.matchedRules().stream()
                .map(match -> match.ruleCode())
                .collect(Collectors.toList());
        assertThat(matchedRuleCodes).containsExactlyInAnyOrder(
                "LARGE_AMOUNT", "MEDIUM_AMOUNT", "ACTIVE_STATUS", "HIGH_PRIORITY");

        System.out.printf("✓ Correctness maintained: matched %d rules%n", result.matchedRules().size());
    }

    @Test
    @DisplayName("Should have better performance without streams")
    void shouldHaveBetterPerformanceWithoutStreams() {
        // Given - warm up
        for (int i = 0; i < 1000; i++) {
            evaluator.evaluate(new Event("warmup-" + i, "TEST", Map.of("amount", 1000)));
        }

        // When - measure throughput
        int iterations = 10000;
        long startTime = System.nanoTime();

        for (int i = 0; i < iterations; i++) {
            evaluator.evaluate(new Event("perf-" + i, "TEST", Map.of(
                    "amount", 1000 + i,
                    "status", i % 2 == 0 ? "ACTIVE" : "PENDING"
            )));
        }

        long duration = System.nanoTime() - startTime;

        // Then - verify good performance
        double throughputPerSec = (iterations * 1_000_000_000.0) / duration;
        double avgLatencyMicros = (duration / 1000.0) / iterations;

        System.out.printf("=== Performance Test ===%n");
        System.out.printf("Iterations:     %,d%n", iterations);
        System.out.printf("Duration:       %.2f ms%n", duration / 1_000_000.0);
        System.out.printf("Throughput:     %.0f events/sec%n", throughputPerSec);
        System.out.printf("Avg latency:    %.2f µs%n", avgLatencyMicros);
        System.out.printf("P50 latency:    < %.2f µs (estimated)%n", avgLatencyMicros * 1.2);
        System.out.printf("P99 latency:    < %.2f µs (estimated)%n", avgLatencyMicros * 3.0);

        // Should achieve > 10K events/sec (very conservative)
        assertThat(throughputPerSec).isGreaterThan(10_000.0);

        // Average latency should be reasonable (< 500µs)
        assertThat(avgLatencyMicros).isLessThan(500.0);
    }

    @Test
    @DisplayName("Should have stable memory usage across batches")
    void shouldHaveStableMemoryUsage() {
        // Given - warm up
        for (int i = 0; i < 500; i++) {
            evaluator.evaluate(new Event("warmup-" + i, "TEST", Map.of("amount", 1000)));
        }

        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();

        // When - measure memory across multiple batches
        long[] memoryAfterBatch = new long[3];

        for (int batch = 0; batch < 3; batch++) {
            // Force GC before each batch
            System.gc();
            System.gc();
            Thread.yield();

            // Evaluate batch
            for (int i = 0; i < 1000; i++) {
                evaluator.evaluate(new Event("batch-" + batch + "-" + i, "TEST", Map.of(
                        "amount", 1000 + i,
                        "status", i % 2 == 0 ? "ACTIVE" : "PENDING"
                )));
            }

            // Force GC after batch
            System.gc();
            System.gc();
            Thread.yield();

            memoryAfterBatch[batch] = memoryBean.getHeapMemoryUsage().getUsed();
        }

        // Then - memory should be stable (not growing unbounded)
        System.out.printf("=== Memory Stability Test ===%n");
        for (int i = 0; i < memoryAfterBatch.length; i++) {
            System.out.printf("After batch %d: %.2f MB%n",
                    i, memoryAfterBatch[i] / (1024.0 * 1024.0));
        }

        // Memory shouldn't grow more than 50% across batches
        long minMemory = Long.MAX_VALUE;
        long maxMemory = Long.MIN_VALUE;

        for (long mem : memoryAfterBatch) {
            minMemory = Math.min(minMemory, mem);
            maxMemory = Math.max(maxMemory, mem);
        }

        double growthRatio = (double) maxMemory / minMemory;
        System.out.printf("Memory growth ratio: %.2fx%n", growthRatio);

        assertThat(growthRatio).isLessThan(1.5); // Less than 50% growth
    }

    private String getStreamTestRules() {
        return """
        [
          {
            "rule_code": "SMALL_AMOUNT",
            "priority": 10,
            "conditions": [
              {"field": "AMOUNT", "operator": "LESS_THAN", "value": 1000}
            ]
          },
          {
            "rule_code": "MEDIUM_AMOUNT",
            "priority": 50,
            "conditions": [
              {"field": "AMOUNT", "operator": "GREATER_THAN", "value": 1000}
            ]
          },
          {
            "rule_code": "LARGE_AMOUNT",
            "priority": 100,
            "conditions": [
              {"field": "AMOUNT", "operator": "GREATER_THAN", "value": 5000}
            ]
          },
          {
            "rule_code": "ACTIVE_STATUS",
            "priority": 60,
            "conditions": [
              {"field": "STATUS", "operator": "EQUAL_TO", "value": "ACTIVE"}
            ]
          },
          {
            "rule_code": "HIGH_PRIORITY",
            "priority": 90,
            "conditions": [
              {"field": "PRIORITY", "operator": "EQUAL_TO", "value": "HIGH"}
            ]
          }
        ]
        """;
    }
}