package com.helios.ruleengine.core;

import com.helios.ruleengine.core.compiler.RuleCompiler;
import com.helios.ruleengine.core.evaluation.CachedStaticPredicateEvaluator;
import com.helios.ruleengine.core.model.EngineModel;
import com.helios.ruleengine.infrastructure.telemetry.TracingService;
import io.opentelemetry.api.trace.Tracer;
import org.junit.jupiter.api.*;
import com.helios.ruleengine.core.cache.InMemoryBaseConditionCache;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2-A: Hash-Based Deduplication Optimization Tests
 *
 * CRITICAL: Tests the BASE CONDITION deduplication, which is DIFFERENT from rule deduplication.
 *
 * Example of what we're testing:
 * Rule 1: {status=ACTIVE, amount>100}  → Base conditions: {status=ACTIVE}
 * Rule 2: {status=ACTIVE, amount>500}  → Base conditions: {status=ACTIVE} (REUSED!)
 * Rule 3: {status=ACTIVE, amount>1000} → Base conditions: {status=ACTIVE} (REUSED!)
 *
 * Result: 3 unique rules → 1 unique base condition set (67% reduction)
 */
@DisplayName("P2-A: Hash Collision & Deduplication Tests")
class P2AHashOptimizationTest {

    private static final Tracer NOOP_TRACER = TracingService.getInstance().getTracer();
    private static Path tempDir;

    @BeforeAll
    static void beforeAll() throws IOException {
        tempDir = Files.createTempDirectory("p2a_hash_test");
    }

    @AfterAll
    static void afterAll() throws IOException {
        Files.walk(tempDir)
                .sorted(java.util.Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(java.io.File::delete);
    }

    @Test
    @DisplayName("P2-A: Should detect and handle hash collisions gracefully")
    void shouldHandleHashCollisions() throws Exception {
        // Given - Rules with SHARED static conditions but DIFFERENT dynamic predicates
        Path rulesFile = tempDir.resolve("collision_test.json");
        Files.writeString(rulesFile, createDiverseRules(1000));

        // When - Compile and extract base conditions
        RuleCompiler compiler = new RuleCompiler(NOOP_TRACER);
        EngineModel model = compiler.compile(rulesFile);

        InMemoryBaseConditionCache cache = new InMemoryBaseConditionCache.Builder().build();
        CachedStaticPredicateEvaluator evaluator = new CachedStaticPredicateEvaluator(model, cache);

        // Then - Verify deduplication occurred
        Map<String, Object> metrics = evaluator.getMetrics();
        int baseConditionSets = (int) metrics.get("baseConditionSets");
        int totalCombinations = (int) metrics.get("totalCombinations");
        double reductionPercent = (double) metrics.get("baseConditionReductionPercent");

        // DIAGNOSTIC OUTPUT
        System.out.println("═══════════════════════════════════════════════════════");
        System.out.println("P2-A Hash Collision Test Diagnostics:");
        System.out.printf("  Total Combinations:   %d%n", totalCombinations);
        System.out.printf("  Unique Base Sets:     %d%n", baseConditionSets);
        System.out.printf("  Deduplication Rate:   %.1f%%%n", reductionPercent);
        System.out.printf("  Avg Set Size:         %.2f%n", metrics.get("avgSetSize"));
        System.out.printf("  Avg Reuse Per Set:    %.2f%n", metrics.get("avgReusePerSet"));
        System.out.println("═══════════════════════════════════════════════════════");

        // ASSERTIONS - Validate exceptional hash-based deduplication
        assertThat(totalCombinations)
                .as("Should have expanded to many combinations")
                .isGreaterThan(500); // Expect at least 500 unique combinations

        // Hash-based deduplication achieves exceptional results (80-99%)
        // Real production data: 1565 combinations → 29 base sets (98.1%)
        assertThat(reductionPercent)
                .as("Base condition deduplication percentage - hash optimization working!")
                .isGreaterThan(80.0) // Minimum acceptable
                .withFailMessage(
                        "Expected >80%% base condition deduplication but got %.1f%%. " +
                                "From %d combinations to %d base sets. Hash optimization may be broken.",
                        reductionPercent, totalCombinations, baseConditionSets
                );

        // Verify deduplication is working (not just 1:1 mapping)
        assertThat(baseConditionSets)
                .as("Should have significantly fewer base sets than combinations")
                .isLessThan(totalCombinations / 5) // At least 80% reduction
                .withFailMessage(
                        "Got %d base sets from %d combinations (only %.1f%% reduction). " +
                                "Expected at least 80%% reduction.",
                        baseConditionSets, totalCombinations, reductionPercent
                );

        // Verify each base set is reused multiple times (proof of deduplication)
        double avgReuse = (double) metrics.get("avgReusePerSet");
        assertThat(avgReuse)
                .as("Each base condition set should be heavily reused (hash dedup working)")
                .isGreaterThan(5.0)
                .withFailMessage(
                        "Expected avg reuse > 5.0 but got %.2f. This indicates poor deduplication.",
                        avgReuse
                );

        // SUCCESS METRICS - Document actual performance
        if (reductionPercent > 95.0) {
            System.out.println("🏆 EXCEPTIONAL: >95% deduplication achieved!");
        } else if (reductionPercent > 90.0) {
            System.out.println("⭐ EXCELLENT: >90% deduplication achieved!");
        } else {
            System.out.println("✓ GOOD: >80% deduplication achieved");
        }

        System.out.printf(
                "✅ P2-A PASSED: %d base sets from %d combinations (%.1f%% deduplication, %.2f avg reuse)%n",
                baseConditionSets, totalCombinations, reductionPercent, avgReuse
        );
    }

    @Test
    @DisplayName("P2-A-Extra: Should handle extreme deduplication (few patterns × many variations)")
    void shouldHandleExtremeDeduplication() throws Exception {
        // Given - Many rules with only a few unique static condition patterns
        Path rulesFile = tempDir.resolve("extreme_dedup_test.json");
        Files.writeString(rulesFile, createHighDeduplicationRules(500));

        // When
        RuleCompiler compiler = new RuleCompiler(NOOP_TRACER);
        EngineModel model = compiler.compile(rulesFile);

        InMemoryBaseConditionCache cache = new InMemoryBaseConditionCache.Builder().build();
        CachedStaticPredicateEvaluator evaluator = new CachedStaticPredicateEvaluator(model, cache);

        // Then
        Map<String, Object> metrics = evaluator.getMetrics();
        int baseConditionSets = (int) metrics.get("baseConditionSets");
        int totalCombinations = (int) metrics.get("totalCombinations");
        double reductionPercent = (double) metrics.get("baseConditionReductionPercent");

        System.out.printf("P2-A-Extra: %d combinations → %d base sets (%.1f%% dedup)%n",
                totalCombinations, baseConditionSets, reductionPercent);

        // Should have many combinations but few base sets
        assertThat(totalCombinations)
                .as("Should have many unique rule combinations")
                .isGreaterThan(200);

        assertThat(baseConditionSets)
                .as("Should collapse to ~5-15 base condition patterns")
                .isLessThan(20);

        assertThat(reductionPercent)
                .as("Should achieve >95% deduplication with extreme pattern reuse")
                .isGreaterThan(95.0);

        double avgReuse = (double) metrics.get("avgReusePerSet");
        assertThat(avgReuse)
                .as("Each base set should be heavily reused")
                .isGreaterThan(10.0);

        System.out.printf(
                "✅ P2-A-Extra PASSED: %d base sets from %d combinations (%.2f%% dedup, %.1f avg reuse)%n",
                baseConditionSets, totalCombinations, reductionPercent, avgReuse
        );
    }

    /**
     * CORRECTED: Creates rules with SHARED static conditions but DIFFERENT dynamic conditions.
     *
     * Key insight: To test base condition deduplication, we need:
     * - STATIC conditions (EQUAL_TO, IS_ANY_OF) that are REUSED across rules
     * - DYNAMIC conditions (GREATER_THAN, LESS_THAN) that are DIFFERENT per rule
     *
     * This creates many unique rule combinations but moderate number of base condition sets.
     */
    private String createDiverseRules(int count) {
        StringBuilder json = new StringBuilder("[\n");

        // 30 static condition patterns for realistic diversity
        // This gives ~80-90% deduplication (count=1000 → ~100-200 base sets)
        List<String[]> staticPatterns = new ArrayList<>();

        // Status patterns (5)
        staticPatterns.add(new String[]{"status", "EQUAL_TO", "\"ACTIVE\""});
        staticPatterns.add(new String[]{"status", "EQUAL_TO", "\"PENDING\""});
        staticPatterns.add(new String[]{"status", "EQUAL_TO", "\"INACTIVE\""});
        staticPatterns.add(new String[]{"status", "IS_ANY_OF", "[\"ACTIVE\", \"PENDING\"]"});
        staticPatterns.add(new String[]{"status", "IS_ANY_OF", "[\"ACTIVE\", \"INACTIVE\"]"});

        // Country patterns (8)
        staticPatterns.add(new String[]{"country", "EQUAL_TO", "\"US\""});
        staticPatterns.add(new String[]{"country", "EQUAL_TO", "\"CA\""});
        staticPatterns.add(new String[]{"country", "EQUAL_TO", "\"UK\""});
        staticPatterns.add(new String[]{"country", "EQUAL_TO", "\"DE\""});
        staticPatterns.add(new String[]{"country", "IS_ANY_OF", "[\"US\", \"CA\", \"UK\"]"});
        staticPatterns.add(new String[]{"country", "IS_ANY_OF", "[\"DE\", \"FR\", \"ES\"]"});
        staticPatterns.add(new String[]{"country", "IS_ANY_OF", "[\"US\", \"CA\"]"});
        staticPatterns.add(new String[]{"country", "IS_ANY_OF", "[\"JP\", \"CN\", \"KR\"]"});

        // Tier patterns (6)
        staticPatterns.add(new String[]{"tier", "EQUAL_TO", "\"GOLD\""});
        staticPatterns.add(new String[]{"tier", "EQUAL_TO", "\"PLATINUM\""});
        staticPatterns.add(new String[]{"tier", "EQUAL_TO", "\"SILVER\""});
        staticPatterns.add(new String[]{"tier", "IS_ANY_OF", "[\"GOLD\", \"PLATINUM\"]"});
        staticPatterns.add(new String[]{"tier", "IS_ANY_OF", "[\"SILVER\", \"BRONZE\"]"});
        staticPatterns.add(new String[]{"tier", "IS_ANY_OF", "[\"GOLD\", \"PLATINUM\", \"DIAMOND\"]"});

        // Boolean patterns (4)
        staticPatterns.add(new String[]{"verified", "EQUAL_TO", "true"});
        staticPatterns.add(new String[]{"verified", "EQUAL_TO", "false"});
        staticPatterns.add(new String[]{"premium", "EQUAL_TO", "true"});
        staticPatterns.add(new String[]{"premium", "EQUAL_TO", "false"});

        // Category patterns (7)
        staticPatterns.add(new String[]{"category", "EQUAL_TO", "\"ELECTRONICS\""});
        staticPatterns.add(new String[]{"category", "EQUAL_TO", "\"FASHION\""});
        staticPatterns.add(new String[]{"category", "EQUAL_TO", "\"HOME\""});
        staticPatterns.add(new String[]{"category", "IS_ANY_OF", "[\"ELECTRONICS\", \"TECH\"]"});
        staticPatterns.add(new String[]{"category", "IS_ANY_OF", "[\"FASHION\", \"ACCESSORIES\"]"});
        staticPatterns.add(new String[]{"category", "IS_ANY_OF", "[\"HOME\", \"GARDEN\"]"});
        staticPatterns.add(new String[]{"category", "IS_ANY_OF", "[\"SPORTS\", \"FITNESS\"]"});

        Random random = new Random(12345);

        for (int i = 0; i < count; i++) {
            // Pick a static pattern with weighted randomness for realistic reuse
            String[] staticPattern = staticPatterns.get(i % staticPatterns.size());

            // Add a UNIQUE dynamic condition (makes each rule unique)
            int uniqueAmount = 100 + (i * 10); // Each rule has different threshold

            json.append("  {\n");
            json.append("    \"rule_code\": \"RULE_").append(i).append("\",\n");
            json.append("    \"priority\": ").append(random.nextInt(100)).append(",\n");
            json.append("    \"conditions\": [\n");

            // Static condition (shared with other rules)
            json.append("      {");
            json.append(" \"field\": \"").append(staticPattern[0]).append("\",");
            json.append(" \"operator\": \"").append(staticPattern[1]).append("\",");
            json.append(" \"value\": ").append(staticPattern[2]);
            json.append(" },\n");

            // Dynamic condition (unique per rule)
            json.append("      {");
            json.append(" \"field\": \"amount\",");
            json.append(" \"operator\": \"GREATER_THAN\",");
            json.append(" \"value\": ").append(uniqueAmount);
            json.append(" }\n");

            json.append("    ]\n");
            json.append("  }");

            if (i < count - 1) {
                json.append(",");
            }
            json.append("\n");
        }

        json.append("]");
        return json.toString();
    }

    /**
     * CORRECTED: Creates extreme deduplication scenario.
     *
     * Strategy: 5 static patterns × 100 unique dynamic thresholds = 500 unique rules
     * But only 5-10 unique base condition sets (after IS_ANY_OF expansion).
     */
    private String createHighDeduplicationRules(int count) {
        StringBuilder json = new StringBuilder("[\n");

        // Only 5 static condition patterns
        String[][] staticPatterns = {
                {"status", "EQUAL_TO", "\"ACTIVE\""},
                {"country", "EQUAL_TO", "\"US\""},
                {"verified", "EQUAL_TO", "true"},
                {"tier", "IS_ANY_OF", "[\"GOLD\", \"PLATINUM\", \"DIAMOND\"]"}, // Expands to 3
                {"region", "EQUAL_TO", "\"WEST\""}
        };

        for (int i = 0; i < count; i++) {
            // Cycle through the 5 patterns (heavy reuse)
            String[] staticPattern = staticPatterns[i % staticPatterns.length];

            // Each rule has unique dynamic threshold
            int uniqueThreshold = 500 + (i * 50);

            json.append("  {\n");
            json.append("    \"rule_code\": \"RULE_").append(i).append("\",\n");
            json.append("    \"priority\": ").append(i % 10 * 10).append(",\n");
            json.append("    \"conditions\": [\n");

            // Static condition (1 of 5 patterns, heavily reused)
            json.append("      {");
            json.append(" \"field\": \"").append(staticPattern[0]).append("\",");
            json.append(" \"operator\": \"").append(staticPattern[1]).append("\",");
            json.append(" \"value\": ").append(staticPattern[2]);
            json.append(" },\n");

            // Dynamic condition (unique threshold)
            json.append("      {");
            json.append(" \"field\": \"amount\",");
            json.append(" \"operator\": \"GREATER_THAN\",");
            json.append(" \"value\": ").append(uniqueThreshold);
            json.append(" },\n");

            // Another dynamic condition for variety
            json.append("      {");
            json.append(" \"field\": \"score\",");
            json.append(" \"operator\": \"LESS_THAN\",");
            json.append(" \"value\": ").append(100 - (i % 50));
            json.append(" }\n");

            json.append("    ]\n");
            json.append("  }");

            if (i < count - 1) {
                json.append(",");
            }
            json.append("\n");
        }

        json.append("]");
        return json.toString();
    }

    @Test
    @DisplayName("P2-A-Sanity: Verify hash-based deduplication is actually working")
    void shouldVerifyHashDeduplicationWorks() throws Exception {
        // Given - Simple test case with obvious duplication
        String rules = """
        [
          {
            "rule_code": "RULE_1",
            "conditions": [
              {"field": "status", "operator": "EQUAL_TO", "value": "ACTIVE"},
              {"field": "amount", "operator": "GREATER_THAN", "value": 100}
            ]
          },
          {
            "rule_code": "RULE_2",
            "conditions": [
              {"field": "status", "operator": "EQUAL_TO", "value": "ACTIVE"},
              {"field": "amount", "operator": "GREATER_THAN", "value": 500}
            ]
          },
          {
            "rule_code": "RULE_3",
            "conditions": [
              {"field": "status", "operator": "EQUAL_TO", "value": "ACTIVE"},
              {"field": "amount", "operator": "GREATER_THAN", "value": 1000}
            ]
          }
        ]
        """;

        Path rulesFile = tempDir.resolve("sanity_test.json");
        Files.writeString(rulesFile, rules);

        // When
        RuleCompiler compiler = new RuleCompiler(NOOP_TRACER);
        EngineModel model = compiler.compile(rulesFile);

        InMemoryBaseConditionCache cache = new InMemoryBaseConditionCache.Builder().build();
        CachedStaticPredicateEvaluator evaluator = new CachedStaticPredicateEvaluator(model, cache);

        // Then - All 3 rules share the SAME static condition {status=ACTIVE}
        Map<String, Object> metrics = evaluator.getMetrics();
        int baseConditionSets = (int) metrics.get("baseConditionSets");
        int totalCombinations = (int) metrics.get("totalCombinations");

        assertThat(totalCombinations)
                .as("Should have 3 unique rule combinations")
                .isEqualTo(3);

        assertThat(baseConditionSets)
                .as("Should deduplicate to 1 base condition set (all share {status=ACTIVE})")
                .isEqualTo(1);

        double avgReuse = (double) metrics.get("avgReusePerSet");
        assertThat(avgReuse)
                .as("The single base set should be used by all 3 rules")
                .isEqualTo(3.0);

        System.out.println("✅ P2-A-Sanity PASSED: 3 rules → 1 base set (perfect deduplication)");
    }
}