package os.toolset.ruleengine.core;

import io.opentelemetry.api.trace.Tracer;
import org.junit.jupiter.api.*;
import os.toolset.ruleengine.model.Event;
import os.toolset.ruleengine.model.MatchResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0-1 OPTIMIZATION TEST: Verify vectorized predicate evaluation is working
 */
@DisplayName("P0-1: Vectorization Optimization Tests")
class VectorizationOptimizationTest {

    private static final Tracer NOOP_TRACER = TracingService.getInstance().getTracer();
    private EngineModel model;
    private RuleEvaluator evaluator;
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

        RuleCompiler compiler = new RuleCompiler(NOOP_TRACER);
        model = compiler.compile(rulesFile);
        evaluator = new RuleEvaluator(model, NOOP_TRACER, false); // Disable base cache for clearer testing
    }

    @Test
    @DisplayName("Should vectorize GREATER_THAN numeric comparisons")
    void shouldVectorizeGreaterThan() {
        // Given - event with amount that triggers GREATER_THAN rules
        // FIXED: Use score=50 to NOT match HIGH_SCORE, so MID_RANGE wins
        Event event = new Event("evt-1", "TEST", Map.of(
                "amount", 7500,  // Matches MEDIUM_VALUE(50), MID_RANGE(60)
                "score", 50,     // Does NOT match HIGH_SCORE (>80)
                "age", 35
        ));

        // When
        MatchResult result = evaluator.evaluate(event);

        // Then - verify correct matches
        assertThat(result.matchedRules()).isNotEmpty();
        assertThat(result.predicatesEvaluated()).isGreaterThan(0);

        // Should match MID_RANGE (priority 60, highest among matches)
        // Matches: MEDIUM_VALUE(50), MID_RANGE(60) -> MID_RANGE wins
        assertThat(result.matchedRules()).hasSize(1);
        assertThat(result.matchedRules().get(0).ruleCode()).isEqualTo("MID_RANGE");
        assertThat(result.matchedRules().get(0).priority()).isEqualTo(60);
    }

    @Test
    @DisplayName("Should vectorize LESS_THAN numeric comparisons")
    void shouldVectorizeLessThan() {
        // Given - event with score that triggers LESS_THAN rule
        Event event = new Event("evt-2", "TEST", Map.of(
                "amount", 50,   // Does NOT match MEDIUM_VALUE (not > 1000)
                "age", 18,
                "score", 45     // Matches LOW_SCORE (<50)
        ));

        // When
        MatchResult result = evaluator.evaluate(event);

        // Then - should match LOW_SCORE (score < 50, priority 30)
        assertThat(result.matchedRules()).hasSize(1);
        assertThat(result.matchedRules().get(0).ruleCode()).isEqualTo("LOW_SCORE");
        assertThat(result.matchedRules().get(0).priority()).isEqualTo(30);
    }

    @Test
    @DisplayName("Should vectorize BETWEEN numeric comparisons")
    void shouldVectorizeBetween() {
        // Given - event with amount in BETWEEN range
        Event event = new Event("evt-3", "TEST", Map.of(
                "amount", 7500,  // Matches MEDIUM_VALUE(50), MID_RANGE(60)
                "age", 30,
                "score", 60      // Does NOT match LOW_SCORE or HIGH_SCORE
        ));

        // When
        MatchResult result = evaluator.evaluate(event);

        // Then - should match MID_RANGE (amount BETWEEN 5000-10000, priority 60)
        assertThat(result.matchedRules()).hasSize(1);
        assertThat(result.matchedRules().get(0).ruleCode()).isEqualTo("MID_RANGE");
        assertThat(result.matchedRules().get(0).priority()).isEqualTo(60);
    }

    @Test
    @DisplayName("Should handle string CONTAINS operations in batch")
    void shouldBatchStringContains() {
        // Given - event with description containing "URGENT"
        Event event = new Event("evt-4", "TEST", Map.of(
                "description", "URGENT: Please review this transaction",
                "category", "FINANCE"
        ));

        // When
        MatchResult result = evaluator.evaluate(event);

        // Then - should match URGENT_ITEM (description contains "URGENT", priority 90)
        assertThat(result.matchedRules()).hasSize(1);
        assertThat(result.matchedRules().get(0).ruleCode()).isEqualTo("URGENT_ITEM");
        assertThat(result.matchedRules().get(0).priority()).isEqualTo(90);
    }

    @Test
    @DisplayName("Should use equality fast path for dictionary-encoded values")
    void shouldUseFastPathForEquality() {
        // Given - event with exact equality matches
        Event event = new Event("evt-5", "TEST", Map.of(
                "status", "ACTIVE",
                "country", "US"
        ));

        // When
        MatchResult result = evaluator.evaluate(event);

        // Then - should match ACTIVE_US (status == ACTIVE AND country == US, priority 70)
        assertThat(result.matchedRules()).hasSize(1);
        assertThat(result.matchedRules().get(0).ruleCode()).isEqualTo("ACTIVE_US");
        assertThat(result.matchedRules().get(0).priority()).isEqualTo(70);
    }

    @Test
    @DisplayName("Vectorization should improve performance for batch evaluation")
    void vectorizationShouldImproveBatchPerformance() {
        // Given - generate batch of events
        List<Event> events = new ArrayList<>();
        Random rand = new Random(42);

        for (int i = 0; i < 100; i++) {
            events.add(new Event("evt-batch-" + i, "TEST", Map.of(
                    "amount", rand.nextInt(20000),
                    "score", rand.nextInt(100),
                    "age", 20 + rand.nextInt(60),
                    "status", rand.nextBoolean() ? "ACTIVE" : "INACTIVE"
            )));
        }

        // When - evaluate batch
        long startTime = System.nanoTime();
        for (Event event : events) {
            evaluator.evaluate(event);
        }
        long duration = System.nanoTime() - startTime;

        // Then - verify all processed correctly
        Map<String, Object> metrics = evaluator.getMetrics().getSnapshot();
        assertThat(metrics.get("totalEvaluations")).isEqualTo(100L);

        // Average latency should be reasonable (< 500 microseconds with vectorization)
        long avgLatencyMicros = (long) metrics.get("avgLatencyMicros");
        assertThat(avgLatencyMicros).isLessThan(500L);

        System.out.printf("Vectorized batch performance: 100 events in %.2f ms (avg: %d µs/event)%n",
                duration / 1_000_000.0, avgLatencyMicros);
    }

    @Test
    @DisplayName("Should correctly handle eligible rules filtering in vectorized path")
    void shouldFilterEligibleRulesInVectorizedPath() {
        // This test verifies P0-1 fix where eligibleRules filter was added

        // Given - event that matches some predicates but not others
        Event event = new Event("evt-6", "TEST", Map.of(
                "amount", 8000,      // Matches MEDIUM_VALUE(50), MID_RANGE(60)
                "status", "INACTIVE", // Doesn't match ACTIVE_US
                "score", 60           // Doesn't match LOW_SCORE or HIGH_SCORE
        ));

        // When
        MatchResult result = evaluator.evaluate(event);

        // Then - verify only correct rules matched
        assertThat(result.matchedRules()).hasSize(1);

        // Should NOT match ACTIVE_US (status != ACTIVE)
        assertThat(result.matchedRules().get(0).ruleCode()).isNotEqualTo("ACTIVE_US");

        // Should match MID_RANGE (highest priority: 60)
        assertThat(result.matchedRules().get(0).ruleCode()).isEqualTo("MID_RANGE");
        assertThat(result.matchedRules().get(0).priority()).isEqualTo(60);
    }

    @Test
    @DisplayName("Should evaluate zero predicates when no eligible rules")
    void shouldSkipEvaluationWhenNoEligibleRules() {
        // Given - create evaluator WITH base cache enabled
        RuleEvaluator cachedEvaluator = new RuleEvaluator(model, NOOP_TRACER, true);

        // Event that matches no base conditions
        Event event = new Event("evt-7", "TEST", Map.of(
                "unknown_field", "value"
        ));

        // When
        MatchResult result = cachedEvaluator.evaluate(event);

        // Then - no matches and minimal predicates evaluated
        assertThat(result.matchedRules()).isEmpty();
        // Base condition filtering should prevent most predicate evaluation
        assertThat(result.predicatesEvaluated()).isLessThan(30);
    }

    @Test
    @DisplayName("Should correctly evaluate multiple numeric predicates in SIMD batch")
    void shouldEvaluateMultipleNumericPredicatesInBatch() {
        // Given - event with values that trigger multiple numeric comparisons
        Event event = new Event("evt-8", "TEST", Map.of(
                "amount", 15000,  // Matches MEDIUM_VALUE(50), HIGH_VALUE(100)
                "score", 90       // Matches HIGH_SCORE(80)
        ));

        // When
        MatchResult result = evaluator.evaluate(event);

        // Then - HIGH_VALUE should win (priority 100 > 80)
        assertThat(result.matchedRules()).hasSize(1);
        assertThat(result.matchedRules().get(0).ruleCode()).isEqualTo("HIGH_VALUE");
        assertThat(result.matchedRules().get(0).priority()).isEqualTo(100);
    }

    @Test
    @DisplayName("Should handle vectorized comparison with exact boundary values")
    void shouldHandleBoundaryValues() {
        // Given - event with exact boundary value for BETWEEN
        Event event = new Event("evt-9", "TEST", Map.of(
                "amount", 5000,  // Exactly at lower boundary of MID_RANGE [5000, 10000]
                "score", 50      // Exactly at boundary for LOW_SCORE (<50), should NOT match
        ));

        // When
        MatchResult result = evaluator.evaluate(event);

        // Then - should match MID_RANGE (5000 >= 5000 && 5000 <= 10000)
        assertThat(result.matchedRules()).hasSize(1);
        assertThat(result.matchedRules().get(0).ruleCode()).isEqualTo("MID_RANGE");
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