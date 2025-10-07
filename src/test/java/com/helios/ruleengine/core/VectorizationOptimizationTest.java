package com.helios.ruleengine.core;

import com.helios.ruleengine.core.compiler.DefaultRuleCompiler;
import com.helios.ruleengine.core.evaluation.DefaultRuleEvaluator;
import com.helios.ruleengine.core.model.DefaultEngineModel;
import com.helios.ruleengine.infrastructure.telemetry.TracingService;
import io.opentelemetry.api.trace.Tracer;
import org.junit.jupiter.api.*;
import com.helios.ruleengine.model.Event;
import com.helios.ruleengine.model.MatchResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1 VECTORIZATION OPTIMIZATION TESTS
 *
 * Tests validate:
 * - P1-A: Bitwise vectorization filtering reduces branch mispredictions
 * - P1-B: Eligible predicate set caching improves performance
 */
@DisplayName("P1 Vectorization Optimizations")
class VectorizationOptimizationTest {

    private static final Tracer NOOP_TRACER = TracingService.getInstance().getTracer();
    private DefaultEngineModel model;
    private DefaultRuleEvaluator evaluator;
    private static Path tempDir;

    @BeforeAll
    static void beforeAll() throws IOException {
        tempDir = Files.createTempDirectory("vectorization_test");
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
        Path rulesFile = tempDir.resolve("vectorization_rules.json");
        Files.writeString(rulesFile, getVectorizationTestRules());

        DefaultRuleCompiler compiler = new DefaultRuleCompiler(NOOP_TRACER);
        model = compiler.compile(rulesFile);
        evaluator = new DefaultRuleEvaluator(model, NOOP_TRACER, false); // Disable base cache for clearer testing
    }

    @Test
    @DisplayName("P1-A: Should vectorize GREATER_THAN numeric comparisons efficiently")
    void shouldVectorizeGreaterThan() {
        Event event = new Event("evt-1", "TEST", Map.of(
                "amount", 7500,
                "score", 50,
                "age", 35
        ));

        MatchResult result = evaluator.evaluate(event);

        assertThat(result.matchedRules()).isNotEmpty();
        assertThat(result.predicatesEvaluated()).isGreaterThan(0);

        assertThat(result.matchedRules()).hasSize(1);
        assertThat(result.matchedRules().get(0).ruleCode()).isEqualTo("MID_RANGE");
        assertThat(result.matchedRules().get(0).priority()).isEqualTo(60);
    }

    @Test
    @DisplayName("P1-A: Should vectorize LESS_THAN numeric comparisons efficiently")
    void shouldVectorizeLessThan() {
        Event event = new Event("evt-2", "TEST", Map.of(
                "amount", 50,
                "age", 18,
                "score", 45
        ));

        MatchResult result = evaluator.evaluate(event);

        assertThat(result.matchedRules()).hasSize(1);
        assertThat(result.matchedRules().get(0).ruleCode()).isEqualTo("LOW_SCORE");
        assertThat(result.matchedRules().get(0).priority()).isEqualTo(30);
    }

    @Test
    @DisplayName("P1-A: Should vectorize BETWEEN numeric comparisons efficiently")
    void shouldVectorizeBetween() {
        Event event = new Event("evt-3", "TEST", Map.of(
                "amount", 7500,
                "age", 30,
                "score", 60
        ));

        MatchResult result = evaluator.evaluate(event);

        assertThat(result.matchedRules()).hasSize(1);
        assertThat(result.matchedRules().get(0).ruleCode()).isEqualTo("MID_RANGE");
        assertThat(result.matchedRules().get(0).priority()).isEqualTo(60);
    }

    @Test
    @DisplayName("P1-A: Bitwise filtering should improve vectorization performance")
    void bitwiseFilteringShouldImprovePerformance() {
        // Warm up
        for (int i = 0; i < 500; i++) {
            evaluator.evaluate(new Event("warmup-" + i, "TEST", Map.of(
                    "amount", 1000 + (i * 10),
                    "score", i % 100
            )));
        }

        // Measure with vectorized predicates
        int iterations = 5000;
        long startTime = System.nanoTime();

        for (int i = 0; i < iterations; i++) {
            evaluator.evaluate(new Event("perf-" + i, "TEST", Map.of(
                    "amount", 1000 + (i % 20000),
                    "score", i % 100,
                    "age", 20 + (i % 60)
            )));
        }

        long duration = System.nanoTime() - startTime;
        double avgLatencyMicros = (duration / 1000.0) / iterations;

        System.out.printf("P1-A Vectorized performance: %.2f µs/event%n", avgLatencyMicros);

        // Should be fast with vectorization
        assertThat(avgLatencyMicros).isLessThan(100.0);
    }

    @Test
    @DisplayName("P1-B: Eligible predicate set cache should reduce overhead")
    void eligibleSetCacheShouldReduceOverhead() {
        // Create evaluator WITH base cache enabled (to trigger eligible set caching)
        DefaultRuleEvaluator cachedEvaluator = new DefaultRuleEvaluator(model, NOOP_TRACER, true);

        // Warm up to populate cache
        for (int i = 0; i < 1000; i++) {
            cachedEvaluator.evaluate(new Event("warmup-" + i, "TEST", Map.of(
                    "amount", 5000,
                    "status", "ACTIVE"
            )));
        }

        // Evaluate with similar events (should hit eligible set cache)
        for (int i = 0; i < 5000; i++) {
            cachedEvaluator.evaluate(new Event("cached-" + i, "TEST", Map.of(
                    "amount", 5000 + (i % 1000),
                    "status", i % 2 == 0 ? "ACTIVE" : "PENDING"
            )));
        }

        Map<String, Object> metrics = cachedEvaluator.getMetrics().getSnapshot();

        // Should have high cache hit rate
        assertThat(metrics).containsKey("eligibleSetCacheHitRate");
        double hitRate = (double) metrics.get("eligibleSetCacheHitRate");

        System.out.printf("P1-B Eligible set cache hit rate: %.1f%%%n", hitRate);

        // Should hit cache frequently (>80% after warmup)
        assertThat(hitRate).isGreaterThan(80.0);
    }

    @Test
    @DisplayName("P1 Combined: Should achieve 1.5-2x improvement over P0")
    void shouldAchieveCombinedImprovement() {
        // Comprehensive warmup
        for (int i = 0; i < 3000; i++) {
            evaluator.evaluate(new Event("warmup-" + i, "TEST", Map.of(
                    "amount", 1000 + (i % 10000),
                    "score", i % 100,
                    "status", i % 2 == 0 ? "ACTIVE" : "PENDING"
            )));
        }

        // Measure throughput
        int iterations = 50000;
        long startTime = System.nanoTime();

        for (int i = 0; i < iterations; i++) {
            evaluator.evaluate(new Event("perf-" + i, "TEST", Map.of(
                    "amount", 1000 + (i % 10000),
                    "score", i % 100,
                    "age", 20 + (i % 60),
                    "status", i % 2 == 0 ? "ACTIVE" : "PENDING"
            )));
        }

        long duration = System.nanoTime() - startTime;
        double throughputPerSec = (iterations * 1_000_000_000.0) / duration;
        double eventsPerMin = throughputPerSec * 60;

        System.out.printf("%n=== P1 Combined Performance ===%n");
        System.out.printf("Throughput:       %.0f events/sec%n", throughputPerSec);
        System.out.printf("Events/min:       %.1f M/min%n", eventsPerMin / 1_000_000.0);
        System.out.printf("Improvement:      %.1fx over P0 baseline%n",
                throughputPerSec / 40_000.0);

        // P1 Target: 1.5-2x improvement over P0 (40K events/sec)
        // Expected: 60-80K events/sec (3.6-4.8M events/min)
        assertThat(throughputPerSec).isGreaterThan(60_000.0);
        assertThat(eventsPerMin).isGreaterThan(3_600_000.0);

        System.out.printf("✅ P1: Achieved %.1fM events/min%n", eventsPerMin / 1_000_000.0);
    }

    @Test
    @DisplayName("P1: Should handle boundary values correctly")
    void shouldHandleBoundaryValues() {
        Event event = new Event("evt-boundary", "TEST", Map.of(
                "amount", 5000,  // Exactly at MID_RANGE boundary
                "score", 50      // Exactly at LOW_SCORE boundary (should NOT match)
        ));

        MatchResult result = evaluator.evaluate(event);

        assertThat(result.matchedRules()).hasSize(1);
        assertThat(result.matchedRules().get(0).ruleCode()).isEqualTo("MID_RANGE");
    }

    @Test
    @DisplayName("P1: Correctness should be maintained with optimizations")
    void shouldMaintainCorrectness() {
        Event highValue = new Event("evt-high", "TEST", Map.of(
                "amount", 15000,
                "score", 90
        ));

        Event lowValue = new Event("evt-low", "TEST", Map.of(
                "amount", 500,
                "score", 45
        ));

        MatchResult result1 = evaluator.evaluate(highValue);
        MatchResult result2 = evaluator.evaluate(lowValue);

        assertThat(result1.matchedRules().get(0).ruleCode()).isEqualTo("HIGH_VALUE");
        assertThat(result2.matchedRules().get(0).ruleCode()).isEqualTo("LOW_SCORE");
    }

    private String getVectorizationTestRules() {
        return """
        [
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
            "rule_code": "LOW_SCORE",
            "priority": 30,
            "conditions": [
              {"field": "score", "operator": "LESS_THAN", "value": 50}
            ]
          },
          {
            "rule_code": "HIGH_SCORE",
            "priority": 80,
            "conditions": [
              {"field": "score", "operator": "GREATER_THAN", "value": 80}
            ]
          },
          {
            "rule_code": "MID_RANGE",
            "priority": 60,
            "conditions": [
              {"field": "amount", "operator": "BETWEEN", "value": [5000, 10000]}
            ]
          },
          {
            "rule_code": "URGENT_ITEM",
            "priority": 90,
            "conditions": [
              {"field": "description", "operator": "CONTAINS", "value": "URGENT"}
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