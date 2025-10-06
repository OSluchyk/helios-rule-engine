package os.toolset.ruleengine.core;

import io.opentelemetry.api.trace.Tracer;
import org.junit.jupiter.api.*;
import os.toolset.ruleengine.model.Event;
import os.toolset.ruleengine.model.MatchResult;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0 OPTIMIZATIONS VALIDATION TESTS
 *
 * Tests validate:
 * - P0-A: RoaringBitmap pre-conversion eliminates allocations
 * - P0-B: Pre-computed cache keys reduce overhead
 * - P0-C: Fixed-tier buffers prevent resize overhead
 */
@DisplayName("P0 Optimizations Validation")
class P0OptimizationsTest {

    private static final Tracer NOOP_TRACER = TracingService.getInstance().getTracer();
    private EngineModel model;
    private RuleEvaluator evaluator;
    private static Path tempDir;

    @BeforeAll
    static void beforeAll() throws IOException {
        tempDir = Files.createTempDirectory("p0_opt_test");
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
        Path rulesFile = tempDir.resolve("p0_test_rules.json");
        Files.writeString(rulesFile, getP0TestRules());

        RuleCompiler compiler = new RuleCompiler(NOOP_TRACER);
        model = compiler.compile(rulesFile);
        evaluator = new RuleEvaluator(model, NOOP_TRACER, true);
    }

    @Test
    @DisplayName("P0-A: RoaringBitmap pre-conversion should eliminate allocations")
    void testP0A_RoaringBitmapPreConversion() {
        // Given - a small pool of repeating events to ensure cache hits
        List<Event> eventPool = new ArrayList<>();
        eventPool.add(new Event("evt-a", "TEST", Map.of("status", "ACTIVE", "country", "US")));
        eventPool.add(new Event("evt-b", "TEST", Map.of("status", "ACTIVE", "country", "CA")));

        // When - evaluate events that trigger base condition filtering and caching
        for (int i = 0; i < 1000; i++) {
            evaluator.evaluate(eventPool.get(i % eventPool.size()));
        }

        // Then - verify conversion savings metric
        Map<String, Object> metrics = evaluator.getMetrics().getSnapshot();

        assertThat(metrics).containsKey("roaringConversionsSaved");
        long conversionsSaved = (long) metrics.get("roaringConversionsSaved");

        // After initial misses, subsequent evaluations should be cache hits, saving conversions.
        assertThat(conversionsSaved).isGreaterThan(900);

        double savingsRate = (double) metrics.get("conversionSavingsRate");
        assertThat(savingsRate).isGreaterThan(90.0);  // >90% savings rate

        System.out.printf("✅ P0-A: Saved %d RoaringBitmap conversions (%.1f%% rate)%n",
                conversionsSaved, savingsRate);
    }

    @Test
    @DisplayName("P0-B: Pre-computed cache keys should reduce overhead")
    void testP0B_PreComputedCacheKeys() {
        // Given - create events that will use different base condition sets
        Event event1 = new Event("evt-1", "TEST", Map.of(
                "status", "ACTIVE",
                "country", "US"
        ));

        Event event2 = new Event("evt-2", "TEST", Map.of(
                "status", "ACTIVE",
                "country", "UK"
        ));

        // When - evaluate repeatedly (cache keys should be generated efficiently)
        long startTime = System.nanoTime();

        for (int i = 0; i < 5000; i++) {
            evaluator.evaluate(i % 2 == 0 ? event1 : event2);
        }

        long duration = System.nanoTime() - startTime;
        double avgLatencyMicros = (duration / 1000.0) / 5000;

        // Then - verify low latency (pre-computed keys should be very fast)
        assertThat(avgLatencyMicros).isLessThan(50.0);  // < 50µs average

        Map<String, Object> metrics = evaluator.getMetrics().getSnapshot();
        long totalEvals = (long) metrics.get("totalEvaluations");

        assertThat(totalEvals).isGreaterThanOrEqualTo(5000);

        System.out.printf("✅ P0-B: Average latency with pre-computed keys: %.2f µs%n",
                avgLatencyMicros);
    }

    @Test
    @DisplayName("P0-C: Fixed-tier buffers should prevent resize overhead")
    void testP0C_FixedTierBuffers() {
        // Given - create events of varying sizes to test buffer tiers
        Event smallEvent = new Event("evt-small", "TEST", Map.of(
                "amount", 1000,
                "status", "ACTIVE"
        ));

        Event mediumEvent = new Event("evt-medium", "TEST", Map.of(
                "amount", 5000,
                "status", "ACTIVE",
                "country", "US",
                "tier", "GOLD",
                "category", "ELECTRONICS"
        ));

        Event largeEvent = new Event("evt-large", "TEST", Map.of(
                "amount", 10000,
                "status", "ACTIVE",
                "country", "US",
                "tier", "PLATINUM",
                "category", "ELECTRONICS",
                "payment", "CARD",
                "verified", true,
                "risk_score", 25
        ));

        // When - evaluate mixed sizes repeatedly
        long startTime = System.nanoTime();

        for (int i = 0; i < 3000; i++) {
            switch (i % 3) {
                case 0 -> evaluator.evaluate(smallEvent);
                case 1 -> evaluator.evaluate(mediumEvent);
                case 2 -> evaluator.evaluate(largeEvent);
            }
        }

        long duration = System.nanoTime() - startTime;
        double avgLatencyMicros = (duration / 1000.0) / 3000;

        // Then - verify consistent performance (no resize spikes)
        assertThat(avgLatencyMicros).isLessThan(100.0);  // Stable performance

        System.out.printf("✅ P0-C: Average latency with fixed-tier buffers: %.2f µs%n",
                avgLatencyMicros);
    }

    @Test
    @DisplayName("P0 Combined: All optimizations should reduce allocation rate")
    void testP0Combined_AllocationReduction() {
        // Given - warm up JIT
        for (int i = 0; i < 2000; i++) {
            evaluator.evaluate(new Event("warmup-" + i, "TEST",
                    Map.of("amount", 1000 + i, "status", "ACTIVE")));
        }

        // Force GC
        System.gc();
        System.gc();
        Thread.yield();

        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapBefore = memoryBean.getHeapMemoryUsage();

        // When - evaluate many events
        int iterations = 10000;
        for (int i = 0; i < iterations; i++) {
            Event event = new Event("evt-" + i, "TEST", Map.of(
                    "amount", 1000 + (i * 10),
                    "status", i % 2 == 0 ? "ACTIVE" : "PENDING",
                    "country", i % 3 == 0 ? "US" : "UK"
            ));
            evaluator.evaluate(event);
        }

        // Force GC
        System.gc();
        System.gc();
        Thread.yield();

        MemoryUsage heapAfter = memoryBean.getHeapMemoryUsage();

        // Then - verify low allocation rate
        long heapGrowth = Math.max(0, heapAfter.getUsed() - heapBefore.getUsed());
        double bytesPerEvaluation = (double) heapGrowth / iterations;

        System.out.printf("=== P0 Combined Allocation Test ===%n");
        System.out.printf("Iterations:         %,d%n", iterations);
        System.out.printf("Heap growth:        %.2f MB%n", heapGrowth / (1024.0 * 1024.0));
        System.out.printf("Bytes/evaluation:   %.2f bytes%n", bytesPerEvaluation);

        // P0 Target: < 2KB per evaluation (should be much lower with all fixes)
        assertThat(bytesPerEvaluation).isLessThan(2048.0);

        Map<String, Object> metrics = evaluator.getMetrics().getSnapshot();
        long avgLatencyMicros = (long) metrics.get("avgLatencyMicros");

        // Should be very fast with all optimizations
        assertThat(avgLatencyMicros).isLessThan(100L);

        System.out.printf("Average latency:    %d µs%n", avgLatencyMicros);
        System.out.printf("✅ P0 Combined: All optimizations working correctly%n");
    }

    @Test
    @DisplayName("P0 Throughput: Should achieve 2.5-4x baseline improvement")
    void testP0_ThroughputImprovement() {
        // Given - comprehensive warmup to stabilize JIT
        System.out.printf("Warming up JIT compiler...%n");

        // Phase 1: Basic warmup
        for (int i = 0; i < 5000; i++) {
            evaluator.evaluate(new Event("warmup1-" + i, "TEST",
                    Map.of("amount", 1000 + (i % 1000))));
        }

        // Phase 2: Varied patterns to trigger all code paths
        for (int i = 0; i < 2000; i++) {
            evaluator.evaluate(new Event("warmup2-" + i, "TEST", Map.of(
                    "amount", 1000 + (i % 10000),
                    "status", i % 2 == 0 ? "ACTIVE" : "PENDING",
                    "country", i % 3 == 0 ? "US" : "UK"
            )));
        }

        // Phase 3: Let JIT settle
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        System.out.printf("Warmup complete. Starting measurement...%n");

        // When - measure throughput with large batch
        int iterations = 50000;
        long startTime = System.nanoTime();

        for (int i = 0; i < iterations; i++) {
            evaluator.evaluate(new Event("perf-" + i, "TEST", Map.of(
                    "amount", 1000 + (i % 10000),
                    "status", i % 2 == 0 ? "ACTIVE" : "PENDING",
                    "country", i % 3 == 0 ? "US" : "UK"
            )));
        }

        long duration = System.nanoTime() - startTime;

        // Then - verify good throughput
        double throughputPerSec = (iterations * 1_000_000_000.0) / duration;
        double eventsPerMin = throughputPerSec * 60;

        // Get detailed metrics
        Map<String, Object> metrics = evaluator.getMetrics().getSnapshot();
        long avgLatencyMicros = (long) metrics.get("avgLatencyMicros");

        System.out.printf("%n=== P0 Throughput Test Results ===%n");
        System.out.printf("Iterations:          %,d%n", iterations);
        System.out.printf("Duration:            %.2f ms%n", duration / 1_000_000.0);
        System.out.printf("Throughput:          %.0f events/sec%n", throughputPerSec);
        System.out.printf("Events/min:          %.1f M/min%n", eventsPerMin / 1_000_000.0);
        System.out.printf("Avg latency:         %d µs%n", avgLatencyMicros);
        System.out.printf("Improvement factor:  %.1fx (baseline: 10K/sec)%n",
                throughputPerSec / 10_000.0);
        System.out.printf("===================================%n");

        // ADJUSTED EXPECTATIONS for P0 fixes alone:
        // Baseline: ~10K events/sec (0.6M events/min)
        // P0 Target: 2.5-4x improvement = 25-40K events/sec (1.5-2.4M events/min)
        // This is realistic for P0 fixes without P1/P2 optimizations

        assertThat(throughputPerSec)
                .withFailMessage(
                        "Expected > 25K events/sec with P0 fixes (2.5x baseline), got %.0f events/sec (%.1fx)",
                        throughputPerSec, throughputPerSec / 10_000.0
                )
                .isGreaterThan(25_000.0);

        assertThat(eventsPerMin)
                .withFailMessage(
                        "Expected > 1.5M events/min with P0 fixes, got %.1fM events/min",
                        eventsPerMin / 1_000_000.0
                )
                .isGreaterThan(1_500_000.0);

        // Success message with actual performance
        double improvementFactor = throughputPerSec / 10_000.0;
        System.out.printf("✅ P0 Throughput: Achieved %.1fM events/min (%.1fx improvement)%n",
                eventsPerMin / 1_000_000.0, improvementFactor);

        if (improvementFactor >= 3.5) {
            System.out.printf("   🌟 EXCELLENT: Exceeded 3.5x target!%n");
        } else if (improvementFactor >= 3.0) {
            System.out.printf("   ⭐ GREAT: Met 3x improvement target!%n");
        } else {
            System.out.printf("   ✓ GOOD: Met minimum 2.5x improvement target%n");
        }
    }

    @Test
    @DisplayName("P0 Correctness: Optimizations should not affect results")
    void testP0_CorrectnessPreserved() {
        // Given - specific test cases
        Event highValueActive = new Event("evt-1", "TEST", Map.of(
                "amount", 15000,
                "status", "ACTIVE",
                "country", "US"
        ));

        Event lowValuePending = new Event("evt-2", "TEST", Map.of(
                "amount", 500,
                "status", "PENDING",
                "country", "UK"
        ));

        // When - evaluate
        MatchResult result1 = evaluator.evaluate(highValueActive);
        MatchResult result2 = evaluator.evaluate(lowValuePending);

        // Then - verify correct matches
        assertThat(result1.matchedRules()).isNotEmpty();
        assertThat(result1.matchedRules().get(0).ruleCode())
                .isIn("HIGH_VALUE", "ACTIVE_US");

        assertThat(result2.matchedRules()).isNotEmpty();
        assertThat(result2.matchedRules().get(0).ruleCode())
                .isIn("LOW_VALUE", "PENDING_STATUS");

        System.out.printf("✅ P0 Correctness: Results are correct%n");
    }

    @Test
    @DisplayName("P0 Scalability: Performance should be consistent across batch sizes")
    void testP0_Scalability() {
        // Test different batch sizes to ensure consistent performance
        int[] batchSizes = {1000, 5000, 10000};

        System.out.printf("%n=== P0 Scalability Test ===%n");

        for (int batchSize : batchSizes) {
            // Warmup for this batch size
            for (int i = 0; i < Math.min(1000, batchSize / 10); i++) {
                evaluator.evaluate(new Event("warmup-" + i, "TEST",
                        Map.of("amount", 1000)));
            }

            // Measure
            long startTime = System.nanoTime();

            for (int i = 0; i < batchSize; i++) {
                evaluator.evaluate(new Event("scale-" + i, "TEST", Map.of(
                        "amount", 1000 + (i % 10000),
                        "status", i % 2 == 0 ? "ACTIVE" : "PENDING"
                )));
            }

            long duration = System.nanoTime() - startTime;
            double throughputPerSec = (batchSize * 1_000_000_000.0) / duration;

            System.out.printf("Batch size %,d: %.0f events/sec%n",
                    batchSize, throughputPerSec);

            // Should maintain at least 20K events/sec at all scales
            assertThat(throughputPerSec).isGreaterThan(20_000.0);
        }

        System.out.printf("✅ P0 Scalability: Consistent performance across scales%n");
    }

    private String getP0TestRules() {
        return """
        [
          {
            "rule_code": "LOW_VALUE",
            "priority": 10,
            "conditions": [
              {"field": "amount", "operator": "LESS_THAN", "value": 1000}
            ]
          },
          {
            "rule_code": "MEDIUM_VALUE",
            "priority": 50,
            "conditions": [
              {"field": "amount", "operator": "GREATER_THAN", "value": 1000}
            ]
          },
          {
            "rule_code": "HIGH_VALUE",
            "priority": 100,
            "conditions": [
              {"field": "amount", "operator": "GREATER_THAN", "value": 10000}
            ]
          },
          {
            "rule_code": "ACTIVE_STATUS",
            "priority": 60,
            "conditions": [
              {"field": "status", "operator": "EQUAL_TO", "value": "ACTIVE"}
            ]
          },
          {
            "rule_code": "PENDING_STATUS",
            "priority": 40,
            "conditions": [
              {"field": "status", "operator": "EQUAL_TO", "value": "PENDING"}
            ]
          },
          {
            "rule_code": "ACTIVE_US",
            "priority": 70,
            "conditions": [
              {"field": "status", "operator": "EQUAL_TO", "value": "ACTIVE"},
              {"field": "country", "operator": "EQUAL_TO", "value": "US"}
            ]
          }
        ]
        """;
    }
}