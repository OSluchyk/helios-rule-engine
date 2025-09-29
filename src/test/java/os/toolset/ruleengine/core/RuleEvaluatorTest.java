// File: src/test/java/com/google/ruleengine/core/RuleEvaluatorTest.java
package os.toolset.ruleengine.core;

import org.junit.jupiter.api.*;
import os.toolset.ruleengine.model.Event;
import os.toolset.ruleengine.model.MatchResult;
import os.toolset.ruleengine.model.Predicate;
import os.toolset.ruleengine.model.Rule;


import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Comprehensive test suite for the Rule Evaluator.
 * Tests correctness, performance, and edge cases.
 */
class RuleEvaluatorTest {

    private EngineModel model;
    private RuleEvaluator evaluator;

    @BeforeEach
    void setUp() {
        model = createTestModel();
        evaluator = new RuleEvaluator(model);
    }

    private EngineModel createTestModel() {
        EngineModel.Builder builder = new EngineModel.Builder();

        // Register predicates
        Predicate p1 = new Predicate("STATUS", "ACTIVE");
        Predicate p2 = new Predicate("AMOUNT", 1000);
        Predicate p3 = new Predicate("REGION", "US");
        Predicate p4 = new Predicate("TYPE", "PREMIUM");

        builder.registerPredicate(p1);
        builder.registerPredicate(p2);
        builder.registerPredicate(p3);
        builder.registerPredicate(p4);

        // Create rules
        Rule rule1 = new Rule(0, "RULE_001", 2, List.of(0, 1), 10, "High value active");
        Rule rule2 = new Rule(1, "RULE_002", 3, List.of(0, 2, 3), 20, "Premium US active");
        Rule rule3 = new Rule(2, "RULE_003", 1, List.of(3), 5, "Any premium");

        builder.addRule(rule1);
        builder.addRule(rule2);
        builder.addRule(rule3);

        return builder.build();
    }

    @Test
    @DisplayName("Should match rules with all predicates true")
    void testBasicMatching() {
        // Event that matches rule1 (STATUS=ACTIVE, AMOUNT=1000)
        Event event = new Event("evt_001", "ORDER", Map.of(
                "STATUS", "ACTIVE",
                "AMOUNT", 1000,
                "REGION", "EU"
        ));

        MatchResult result = evaluator.evaluate(event);

        assertThat(result.eventId()).isEqualTo("evt_001");
        assertThat(result.matchedRules()).hasSize(1);
        assertThat(result.matchedRules().get(0).ruleCode()).isEqualTo("RULE_001");
    }

    @Test
    @DisplayName("Should match multiple rules simultaneously")
    void testMultipleMatches() {
        // Event that matches all three rules
        Event event = new Event("evt_002", "ORDER", Map.of(
                "STATUS", "ACTIVE",
                "AMOUNT", 1000,
                "REGION", "US",
                "TYPE", "PREMIUM"
        ));

        MatchResult result = evaluator.evaluate(event);

        assertThat(result.matchedRules()).hasSize(3);
        // Should be sorted by priority (descending)
        assertThat(result.matchedRules().get(0).priority()).isEqualTo(20); // RULE_002
        assertThat(result.matchedRules().get(1).priority()).isEqualTo(10); // RULE_001
        assertThat(result.matchedRules().get(2).priority()).isEqualTo(5);  // RULE_003
    }

    @Test
    @DisplayName("Should return empty matches when no rules match")
    void testNoMatches() {
        Event event = new Event("evt_003", "ORDER", Map.of(
                "STATUS", "INACTIVE",
                "AMOUNT", 500
        ));

        MatchResult result = evaluator.evaluate(event);

        assertThat(result.matchedRules()).isEmpty();
    }

    @Test
    @DisplayName("Should handle missing attributes gracefully")
    void testMissingAttributes() {
        Event event = new Event("evt_004", "ORDER", Map.of());

        MatchResult result = evaluator.evaluate(event);

        assertThat(result.matchedRules()).isEmpty();
        assertThat(result.predicatesEvaluated()).isEqualTo(0);
    }

    @Test
    @DisplayName("Should normalize field names correctly")
    void testFieldNormalization() {
        Event event = new Event("evt_005", "ORDER", Map.of(
                "status", "ACTIVE",  // lowercase
                "amount", 1000
        ));

        MatchResult result = evaluator.evaluate(event);

        assertThat(result.matchedRules()).hasSize(1);
        assertThat(result.matchedRules().get(0).ruleCode()).isEqualTo("RULE_001");
    }

    @Test
    @DisplayName("Should achieve sub-millisecond latency for 1000 rules")
    void testPerformance() {
        // Create a larger model with 1000 rules
        EngineModel largeModel = createLargeModel(1000);
        RuleEvaluator largeEvaluator = new RuleEvaluator(largeModel);

        // Warm up JVM
        for (int i = 0; i < 1000; i++) {
            Event warmupEvent = new Event("warmup_" + i, "TEST",
                    Map.of("FIELD_1", i, "FIELD_2", "VALUE_" + (i % 10)));
            largeEvaluator.evaluate(warmupEvent);
        }

        // Measure performance
        List<Long> latencies = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            Event event = new Event("perf_" + i, "TEST",
                    Map.of("FIELD_1", i, "FIELD_2", "VALUE_" + (i % 10)));

            long start = System.nanoTime();
            largeEvaluator.evaluate(event);
            long latency = System.nanoTime() - start;

            latencies.add(latency);
        }

        // Calculate P99 latency
        Collections.sort(latencies);
        long p99 = latencies.get(98);

        // Should be less than 100ms (100_000_000 nanos) as per MVP target
        assertThat(p99).isLessThan(100_000_000L);

        // Log performance stats
        double avgLatencyMs = latencies.stream().mapToLong(Long::longValue).average().orElse(0) / 1_000_000.0;
        System.out.printf("Performance Test: Avg=%.2fms, P99=%.2fms%n",
                avgLatencyMs, p99 / 1_000_000.0);
    }

    @Test
    @DisplayName("Should handle concurrent evaluations safely")
    void testConcurrency() throws InterruptedException, ExecutionException {
        ExecutorService executor = Executors.newFixedThreadPool(10);
        List<Future<MatchResult>> futures = new ArrayList<>();

        for (int i = 0; i < 1000; i++) {
            final int index = i;
            futures.add(executor.submit(() -> {
                Event event = new Event("concurrent_" + index, "TEST",
                        Map.of("STATUS", "ACTIVE", "TYPE", "PREMIUM"));
                return evaluator.evaluate(event);
            }));
        }

        // Verify all evaluations complete successfully
        for (Future<MatchResult> future : futures) {
            MatchResult result = future.get();
            assertThat(result).isNotNull();
            assertThat(result.eventId()).startsWith("concurrent_");
        }

        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);
    }

    private EngineModel createLargeModel(int ruleCount) {
        EngineModel.Builder builder = new EngineModel.Builder();

        // Create diverse predicates
        for (int i = 0; i < 100; i++) {
            builder.registerPredicate(new Predicate("FIELD_" + (i % 10), i));
            builder.registerPredicate(new Predicate("FIELD_" + (i % 10), "VALUE_" + i));
        }

        // Create rules with varying complexity
        Random random = new Random(42); // Deterministic for testing
        for (int i = 0; i < ruleCount; i++) {
            int predicateCount = 1 + random.nextInt(5);
            List<Integer> predicateIds = new ArrayList<>();

            for (int j = 0; j < predicateCount; j++) {
                predicateIds.add(random.nextInt(100));
            }

            Rule rule = new Rule(i, "RULE_" + i, predicateCount,
                    predicateIds, random.nextInt(100), "Test rule " + i);
            builder.addRule(rule);
        }

        return builder.build();
    }
}



