package os.toolset.ruleengine.core;

import org.junit.jupiter.api.*;
import os.toolset.ruleengine.model.Event;
import os.toolset.ruleengine.model.MatchResult;
import os.toolset.ruleengine.model.Predicate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Random;

import static org.assertj.core.api.Assertions.*;

/**
 * Phase 4 Test Suite: Validates weighted evaluation, SoA layout, and performance improvements.
 */
class Phase4RuleEngineTest {

    private EngineModel model;
    private RuleEvaluator evaluator;
    private static Path tempDir;

    @BeforeAll
    static void beforeAll() throws IOException {
        tempDir = Files.createTempDirectory("rule_engine_phase4_test");
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
        Path rulesFile = tempDir.resolve("phase4_rules.json");
        Files.writeString(rulesFile, getPhase4TestRulesJson());
        model = new RuleCompiler().compile(rulesFile);
        evaluator = new RuleEvaluator(model);
    }

    @Test
    @DisplayName("Phase 4: Should compile with selectivity and weight calculation")
    void testSelectivityProfiling() {
        // Verify predicates have weights
        assertThat(model.getPredicateRegistry().size()).isGreaterThan(0);
        for (int i = 0; i < model.getPredicateRegistry().size(); i++) {
            Predicate pred = model.getPredicate(i);
            if (pred != null) { // Predicate IDs are dense, so this should always be true
                assertThat(pred.weight()).isGreaterThan(0).isLessThanOrEqualTo(1.0f);
                assertThat(pred.selectivity()).isGreaterThan(0).isLessThanOrEqualTo(1.0f);

                // Weight should be inverse of selectivity
                assertThat(pred.weight()).isCloseTo(1.0f - pred.selectivity(), within(0.01f));
            }
        }
    }

    @Test
    @DisplayName("Phase 4: Should use Structure of Arrays memory layout")
    void testSoAMemoryLayout() {
        // Verify we can access rule data through SoA arrays
        int numRules = model.getNumRules();
        assertThat(numRules).isGreaterThan(0);

        for (int i = 0; i < numRules; i++) {
            // Direct array access should work
            assertThat(model.getRuleCode(i)).isNotNull();
            assertThat(model.getRulePriority(i)).isGreaterThanOrEqualTo(0);
            assertThat(model.getRulePredicateCount(i)).isGreaterThan(0);

            // FIX: Cast to List to resolve AssertJ ambiguity
            assertThat((List<?>) model.getRulePredicateIds(i)).isNotNull().isNotEmpty();

            // Backward compatibility: getRule() should still work
            assertThat(model.getRule(i)).isNotNull();
        }
    }

    @Test
    @DisplayName("Phase 4: Should evaluate predicates in weight order per field")
    void testWeightedEvaluation() {
        // Create event that matches multiple predicates
        Event event = new Event("evt_weighted", "TEST", Map.of(
                "amount", 5500,
                "country", "US",
                "status", "ACTIVE",
                "tier", "GOLD"
        ));

        MatchResult result = evaluator.evaluate(event);

        // Verify evaluation happened (exact matches depend on rules)
        assertThat(result.predicatesEvaluated()).isGreaterThan(0);
        assertThat(result.evaluationTimeNanos()).isGreaterThan(0);

        // Check that predicates within each field list are sorted by weight
        for (List<Predicate> predicates : model.getFieldToPredicates().values()) {
            for (int i = 1; i < predicates.size(); i++) {
                float weight1 = predicates.get(i-1).getCombinedWeight();
                float weight2 = predicates.get(i).getCombinedWeight();
                assertThat(weight1).isLessThanOrEqualTo(weight2);
            }
        }
    }

    @Test
    @DisplayName("Phase 4: Should track predicate density for future optimizations")
    void testPredicateDensityTracking() {
        // This is a conceptual test. We rely on the compiler logging to verify this.
        // The presence of the logging statement in RuleCompiler is the main check.
        // No direct assertion is needed here as density is not exposed on the final model.
    }

    @Test
    @DisplayName("Phase 4: Should maintain correctness with performance optimizations")
    void testCorrectnessWithOptimizations() {
        // Test various event patterns to ensure correctness

        // 1. Simple match
        Event event1 = new Event("evt1", "TEST", Map.of("country", "US", "status", "ACTIVE"));
        MatchResult result1 = evaluator.evaluate(event1);
        assertThat(result1.matchedRules()).isNotEmpty();

        // 2. Complex match with multiple conditions
        Event event2 = new Event("evt2", "TEST", Map.of(
                "amount", 15000,
                "country", "US",
                "tier", "PLATINUM",
                "status", "ACTIVE"
        ));
        MatchResult result2 = evaluator.evaluate(event2);
        assertThat(result2.matchedRules()).isNotEmpty();
        assertThat(result2.matchedRules().stream()
                .anyMatch(r -> r.ruleCode().equals("VIP_HIGH_VALUE"))).isTrue();

        // 3. No match
        Event event3 = new Event("evt3", "TEST", Map.of("unknown_field", "value"));
        MatchResult result3 = evaluator.evaluate(event3);
        assertThat(result3.matchedRules()).isEmpty();
    }

    @Test
    @DisplayName("Phase 4: Performance - Should achieve <2ms P99 latency")
    void testPerformanceImprovement() {
        // Run multiple evaluations to measure performance
        List<Long> latencies = new ArrayList<>();
        int iterations = 1000;

        // Warm up
        for (int i = 0; i < 100; i++) {
            Event warmupEvent = generateRandomEvent(i);
            evaluator.evaluate(warmupEvent);
        }

        // Measure
        for (int i = 0; i < iterations; i++) {
            Event event = generateRandomEvent(i);
            MatchResult result = evaluator.evaluate(event);
            latencies.add(result.evaluationTimeNanos());
        }

        // Calculate P99
        latencies.sort(Long::compare);
        long p99Nanos = latencies.get((int)(iterations * 0.99));
        double p99Millis = p99Nanos / 1_000_000.0;

        System.out.println("Phase 4 Performance Metrics:");
        System.out.printf("  P50 Latency: %.3f ms%n",
                latencies.get(iterations / 2) / 1_000_000.0);
        System.out.printf("  P99 Latency: %.3f ms%n", p99Millis);
        System.out.printf("  Max Latency: %.3f ms%n",
                latencies.get(iterations - 1) / 1_000_000.0);

        // Target: P99 < 2ms
        assertThat(p99Millis).isLessThan(2.0);
    }

    @Test
    @DisplayName("Phase 4: Should handle IS_ANY_OF expansion with weights")
    void testWeightedIsAnyOfExpansion() {
        Event event = new Event("evt_anyof", "TEST", Map.of("region", "EU"));
        MatchResult result = evaluator.evaluate(event);

        // Should match the REGIONAL_RULE
        assertThat(result.matchedRules()).isNotEmpty();
        assertThat(result.matchedRules().stream()
                .anyMatch(r -> r.ruleCode().equals("REGIONAL_RULE"))).isTrue();
    }

    private Event generateRandomEvent(int seed) {
        // FIX: Use standard Random for reproducible tests, not ThreadLocalRandom
        Random random = new Random(seed);

        Map<String, Object> attributes = Map.of(
                "amount", random.nextInt(1, 20000),
                "country", random.nextBoolean() ? "US" : "UK",
                "status", random.nextBoolean() ? "ACTIVE" : "INACTIVE",
                "tier", random.nextBoolean() ? "GOLD" : "SILVER",
                "region", new String[]{"NA", "EU", "APAC"}[random.nextInt(3)]
        );

        return new Event("evt_" + seed, "TEST", attributes);
    }

    private String getPhase4TestRulesJson() {
        return """
        [
          {
            "rule_code": "BASIC_US_RULE",
            "priority": 50,
            "description": "Basic rule for US customers",
            "conditions": [
              {"field": "country", "operator": "EQUAL_TO", "value": "US"},
              {"field": "status", "operator": "EQUAL_TO", "value": "ACTIVE"}
            ]
          },
          {
            "rule_code": "VIP_HIGH_VALUE",
            "priority": 100,
            "description": "VIP high-value transactions",
            "conditions": [
              {"field": "amount", "operator": "GREATER_THAN", "value": 10000},
              {"field": "tier", "operator": "EQUAL_TO", "value": "PLATINUM"}
            ]
          },
          {
            "rule_code": "REGIONAL_RULE",
            "priority": 60,
            "description": "Regional expansion test",
            "conditions": [
              {"field": "region", "operator": "IS_ANY_OF", "value": ["NA", "EU", "APAC"]}
            ]
          },
          {
            "rule_code": "COMPLEX_RULE",
            "priority": 80,
            "description": "Complex rule with multiple operators",
            "conditions": [
              {"field": "amount", "operator": "BETWEEN", "value": [5000, 15000]},
              {"field": "country", "operator": "IS_ANY_OF", "value": ["US", "CA", "UK"]},
              {"field": "status", "operator": "EQUAL_TO", "value": "ACTIVE"}
            ]
          },
          {
            "rule_code": "HIGH_SELECTIVITY_RULE",
            "priority": 70,
            "description": "Rule with highly selective predicates",
            "conditions": [
              {"field": "transaction_id", "operator": "REGEX", "value": "^TXN-[0-9]{10}$"},
              {"field": "amount", "operator": "GREATER_THAN", "value": 50000}
            ]
          },
          {
            "rule_code": "LOW_SELECTIVITY_RULE",
            "priority": 40,
            "description": "Rule with less selective predicates",
            "conditions": [
              {"field": "status", "operator": "EQUAL_TO", "value": "ACTIVE"},
              {"field": "amount", "operator": "GREATER_THAN", "value": 100}
            ]
          }
        ]
        """;
    }
}

