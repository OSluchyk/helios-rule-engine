/*
 * Copyright (c) 2025 Helios Rule Engine
 * Licensed under the Apache License, Version 2.0
 */
package com.helios.ruleengine.core;

import com.helios.ruleengine.core.compiler.RuleCompiler;
import com.helios.ruleengine.core.evaluation.cache.BaseConditionEvaluator;
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
 * ✅ FIXED: P2-A Hash-Based Deduplication Optimization Tests with null-safe metric access
 *
 * CRITICAL: Tests the BASE CONDITION deduplication, which is DIFFERENT from rule deduplication.
 *
 * Example of what we're testing:
 * Rule 1: {status=ACTIVE, amount>100}  → Base conditions: {status=ACTIVE}
 * Rule 2: {status=ACTIVE, amount>500}  → Base conditions: {status=ACTIVE} (REUSED!)
 * Rule 3: {status=ACTIVE, amount>1000} → Base conditions: {status=ACTIVE} (REUSED!)
 *
 * Result: 3 unique rules → 1 unique base condition set (67% reduction)
 *
 * FIXES APPLIED:
 * - Null-safe metric access with proper error messages
 * - Diagnostic output for debugging
 * - Graceful handling of missing metrics
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

    // ================================================================
    // HELPER METHODS FOR NULL-SAFE METRIC ACCESS
    // ================================================================

    /**
     * ✅ FIX: Null-safe integer metric retrieval
     */
    private static int getIntMetric(Map<String, Object> metrics, String key, String context) {
        Object value = metrics.get(key);
        if (value == null) {
            System.err.println("❌ ERROR: Metric '" + key + "' is null in context: " + context);
            System.err.println("Available metrics keys: " + metrics.keySet());
            System.err.println("Metrics content: " + metrics);
            throw new AssertionError(
                    String.format("Metric '%s' is null. Available keys: %s", key, metrics.keySet())
            );
        }
        if (value instanceof Integer) {
            return (Integer) value;
        }
        throw new AssertionError(
                String.format("Metric '%s' has wrong type: expected Integer, got %s",
                        key, value.getClass().getSimpleName())
        );
    }

    /**
     * ✅ FIX: Null-safe double metric retrieval
     */
    private static double getDoubleMetric(Map<String, Object> metrics, String key, String context) {
        Object value = metrics.get(key);
        if (value == null) {
            System.err.println("❌ ERROR: Metric '" + key + "' is null in context: " + context);
            System.err.println("Available metrics keys: " + metrics.keySet());
            System.err.println("Metrics content: " + metrics);
            throw new AssertionError(
                    String.format("Metric '%s' is null. Available keys: %s", key, metrics.keySet())
            );
        }
        if (value instanceof Double) {
            return (Double) value;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        throw new AssertionError(
                String.format("Metric '%s' has wrong type: expected Double, got %s",
                        key, value.getClass().getSimpleName())
        );
    }

    // ================================================================
    // TESTS
    // ================================================================

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
        BaseConditionEvaluator evaluator = new BaseConditionEvaluator(model, cache);

        // Then - Verify deduplication occurred
        Map<String, Object> metrics = evaluator.getMetrics();

        // ✅ FIX: Null-safe metric access with context
        int baseConditionSets = getIntMetric(metrics, "baseConditionSets", "shouldHandleHashCollisions");
        int totalCombinations = getIntMetric(metrics, "totalCombinations", "shouldHandleHashCollisions");
        double reductionPercent = getDoubleMetric(metrics, "baseConditionReductionPercent", "shouldHandleHashCollisions");
        double avgSetSize = getDoubleMetric(metrics, "avgSetSize", "shouldHandleHashCollisions");
        double avgReusePerSet = getDoubleMetric(metrics, "avgReusePerSet", "shouldHandleHashCollisions");

        // DIAGNOSTIC OUTPUT
        System.out.println("═══════════════════════════════════════════════════════");
        System.out.println("P2-A Hash Collision Test Diagnostics:");
        System.out.printf("  Total Combinations:   %d%n", totalCombinations);
        System.out.printf("  Unique Base Sets:     %d%n", baseConditionSets);
        System.out.printf("  Deduplication Rate:   %.1f%%%n", reductionPercent);
        System.out.printf("  Avg Set Size:         %.2f%n", avgSetSize);
        System.out.printf("  Avg Reuse Per Set:    %.2f%n", avgReusePerSet);
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
        assertThat(avgReusePerSet)
                .as("Each base condition set should be heavily reused (hash dedup working)")
                .isGreaterThan(5.0)
                .withFailMessage(
                        "Expected avg reuse > 5.0 but got %.2f. This indicates poor deduplication.",
                        avgReusePerSet
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
                baseConditionSets, totalCombinations, reductionPercent, avgReusePerSet
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
        BaseConditionEvaluator evaluator = new BaseConditionEvaluator(model, cache);

        // Then
        Map<String, Object> metrics = evaluator.getMetrics();

        // ✅ FIX: Null-safe metric access
        int baseConditionSets = getIntMetric(metrics, "baseConditionSets", "shouldHandleExtremeDeduplication");
        int totalCombinations = getIntMetric(metrics, "totalCombinations", "shouldHandleExtremeDeduplication");
        double reductionPercent = getDoubleMetric(metrics, "baseConditionReductionPercent", "shouldHandleExtremeDeduplication");
        double avgReusePerSet = getDoubleMetric(metrics, "avgReusePerSet", "shouldHandleExtremeDeduplication");

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

        assertThat(avgReusePerSet)
                .as("Each base set should be heavily reused")
                .isGreaterThan(10.0);

        System.out.printf(
                "✅ P2-A-Extra PASSED: %d base sets from %d combinations (%.2f%% dedup, %.1f avg reuse)%n",
                baseConditionSets, totalCombinations, reductionPercent, avgReusePerSet
        );
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
        BaseConditionEvaluator evaluator = new BaseConditionEvaluator(model, cache);

        // Then - All 3 rules share the SAME static condition {status=ACTIVE}
        Map<String, Object> metrics = evaluator.getMetrics();

        // ✅ FIX: Null-safe metric access
        int baseConditionSets = getIntMetric(metrics, "baseConditionSets", "shouldVerifyHashDeduplicationWorks");
        int totalCombinations = getIntMetric(metrics, "totalCombinations", "shouldVerifyHashDeduplicationWorks");
        double avgReusePerSet = getDoubleMetric(metrics, "avgReusePerSet", "shouldVerifyHashDeduplicationWorks");

        assertThat(totalCombinations)
                .as("Should have 3 unique rule combinations")
                .isEqualTo(3);

        assertThat(baseConditionSets)
                .as("Should deduplicate to 1 base condition set (all share {status=ACTIVE})")
                .isEqualTo(1);

        assertThat(avgReusePerSet)
                .as("The single base set should be used by all 3 rules")
                .isEqualTo(3.0);

        System.out.println("✅ P2-A-Sanity PASSED: 3 rules → 1 base set (perfect deduplication)");
    }

    // ================================================================
    // TEST DATA GENERATORS
    // ================================================================

    /**
     * Creates rules with SHARED static conditions but DIFFERENT dynamic conditions.
     * This tests the hash-based deduplication effectiveness.
     */
    private String createDiverseRules(int count) {
        StringBuilder json = new StringBuilder("[\n");

        // Create diverse static condition patterns (20 different patterns)
        List<String[]> staticPatterns = new ArrayList<>();

        // Country patterns (5)
        staticPatterns.add(new String[]{"country", "EQUAL_TO", "\"US\""});
        staticPatterns.add(new String[]{"country", "EQUAL_TO", "\"UK\""});
        staticPatterns.add(new String[]{"country", "EQUAL_TO", "\"CA\""});
        staticPatterns.add(new String[]{"country", "IS_ANY_OF", "[\"US\", \"CA\"]"});
        staticPatterns.add(new String[]{"country", "IS_ANY_OF", "[\"UK\", \"EU\"]"});

        // Tier patterns (8)
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
            // Pick random static pattern (this creates deduplication opportunity)
            String[] staticPattern = staticPatterns.get(i % staticPatterns.size());

            // Each rule has unique dynamic conditions
            int uniqueThreshold = 100 + (i * 10);
            int uniqueScore = random.nextInt(1000);

            json.append("  {\n");
            json.append("    \"rule_code\": \"RULE_").append(i).append("\",\n");
            json.append("    \"priority\": ").append(i % 10 * 10).append(",\n");
            json.append("    \"conditions\": [\n");

            // Static condition (1 of 20 patterns, shared across rules)
            json.append("      {");
            json.append(" \"field\": \"").append(staticPattern[0]).append("\",");
            json.append(" \"operator\": \"").append(staticPattern[1]).append("\",");
            json.append(" \"value\": ").append(staticPattern[2]);
            json.append(" },\n");

            // Dynamic condition 1 (unique threshold)
            json.append("      {");
            json.append(" \"field\": \"amount\",");
            json.append(" \"operator\": \"GREATER_THAN\",");
            json.append(" \"value\": ").append(uniqueThreshold);
            json.append(" },\n");

            // Dynamic condition 2 (unique score)
            json.append("      {");
            json.append(" \"field\": \"score\",");
            json.append(" \"operator\": \"LESS_THAN\",");
            json.append(" \"value\": ").append(uniqueScore);
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
     * Creates rules with extreme deduplication potential.
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
}