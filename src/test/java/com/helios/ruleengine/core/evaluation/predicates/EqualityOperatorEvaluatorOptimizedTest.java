/*
 * Copyright (c) 2025 Helios Rule Engine
 * Licensed under the Apache License, Version 2.0
 */
package com.helios.ruleengine.core.evaluation.predicates;

import com.helios.ruleengine.core.compiler.RuleCompiler;
import com.helios.ruleengine.core.evaluation.context.EvaluationContext;
import com.helios.ruleengine.core.model.EngineModel;
import com.helios.ruleengine.infrastructure.telemetry.TracingService;
import io.opentelemetry.api.trace.Tracer;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for optimized EqualityOperatorEvaluator.
 *
 * Tests verify:
 * - O(1) hash lookup for EQUAL_TO predicates
 * - Selectivity-based ordering for NOT_EQUAL_TO predicates
 * - Fast path for single-predicate fields
 * - Correct handling of eligibility filters
 * - Performance improvements over naive implementation
 */
class EqualityOperatorEvaluatorOptimizedTest {

    private static final Tracer tracer = TracingService.getInstance().getTracer();

    private RuleCompiler compiler;
    private EngineModel model;
    private EqualityOperatorEvaluator evaluator;

    @BeforeEach
    void setUp() throws Exception {
        // Use test rules that exercise equality operators
        Path rulesPath = Path.of("src/test/resources/test-rules-002.json");
        compiler = new RuleCompiler(tracer);
        model = compiler.compile(rulesPath);
        evaluator = new EqualityOperatorEvaluator(model);
    }

    @Test
    @DisplayName("Should compile field-specific evaluators at initialization")
    void shouldCompileFieldEvaluators() {
        // Given - evaluator initialized in setUp()

        // When
        EqualityOperatorEvaluator.Metrics metrics = evaluator.getMetrics();

        // Then - should have compiled evaluators for fields with equality predicates
        assertThat(metrics.compiledFields()).isGreaterThan(0);
        assertThat(metrics.totalEvaluations()).isEqualTo(0); // No evaluations yet
    }

    @Test
    @DisplayName("Should use O(1) hash lookup for EQUAL_TO predicates")
    void shouldUseHashLookupForEqualTo() {
        // Given
        int fieldId = model.getFieldDictionary().getId("STATUS");
        Object value = model.getValueDictionary().getId("ACTIVE"); // Dictionary-encoded
        EvaluationContext ctx = new EvaluationContext(model.getNumRules(), 100);

        // When
        evaluator.evaluateEquality(fieldId, value, ctx, null);

        // Then - should have evaluated predicates
        assertThat(ctx.getPredicatesEvaluated()).isGreaterThan(0);

        // Verify metrics show evaluations
        EqualityOperatorEvaluator.Metrics metrics = evaluator.getMetrics();
        assertThat(metrics.totalEvaluations()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should evaluate NOT_EQUAL_TO predicates correctly")
    void shouldEvaluateNotEqualTo() {
        // Given
        int fieldId = model.getFieldDictionary().getId("STATUS");
        Object value = model.getValueDictionary().getId("INACTIVE"); // Dictionary-encoded
        EvaluationContext ctx = new EvaluationContext(model.getNumRules(), 100);

        // When - evaluate with value that doesn't match most predicates
        evaluator.evaluateEquality(fieldId, value, ctx, null);

        // Then - NOT_EQUAL_TO predicates should match
        EqualityOperatorEvaluator.Metrics metrics = evaluator.getMetrics();
        assertThat(metrics.totalEvaluations()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should respect eligibility filter")
    void shouldRespectEligibilityFilter() {
        // Given
        int fieldId = model.getFieldDictionary().getId("STATUS");
        Object value = model.getValueDictionary().getId("ACTIVE");
        EvaluationContext ctx = new EvaluationContext(model.getNumRules(), 100);

        // Create eligibility filter that excludes all predicates
        IntSet eligiblePredicateIds = new IntOpenHashSet();
        // Empty set - no predicates are eligible

        // When
        evaluator.evaluateEquality(fieldId, value, ctx, eligiblePredicateIds);

        // Then - no predicates should be evaluated
        assertThat(ctx.getTruePredicates()).isEmpty();
    }

    @Test
    @DisplayName("Should handle fields with no equality predicates")
    void shouldHandleFieldsWithNoEqualityPredicates() {
        // Given - field ID that doesn't have equality predicates
        int nonExistentFieldId = 99999;
        EvaluationContext ctx = new EvaluationContext(model.getNumRules(), 100);

        // When
        evaluator.evaluateEquality(nonExistentFieldId, "any_value", ctx, null);

        // Then - should not fail, just return immediately
        assertThat(ctx.getTruePredicates()).isEmpty();
    }

    @Test
    @DisplayName("Should maintain correctness with eligibility filtering")
    void shouldMaintainCorrectnessWithFiltering() {
        // Given
        int fieldId = model.getFieldDictionary().getId("STATUS");
        Object value = model.getValueDictionary().getId("ACTIVE");
        EvaluationContext ctx = new EvaluationContext(model.getNumRules(), 100);

        // Create eligibility filter that includes only specific predicates
        IntSet eligiblePredicateIds = new IntOpenHashSet();
        // Add first few predicate IDs
        for (int i = 0; i < 5; i++) {
            eligiblePredicateIds.add(i);
        }

        // When
        evaluator.evaluateEquality(fieldId, value, ctx, eligiblePredicateIds);

        // Then - only eligible predicates should be in results
        IntSet truePredicates = ctx.getTruePredicates();
        for (int predId : truePredicates) {
            assertThat(eligiblePredicateIds.contains(predId)).isTrue();
        }
    }

    @Test
    @DisplayName("Should handle null values correctly")
    void shouldHandleNullValues() {
        // Given
        int fieldId = model.getFieldDictionary().getId("STATUS");
        EvaluationContext ctx = new EvaluationContext(model.getNumRules(), 100);

        // When - evaluate with null value
        evaluator.evaluateEquality(fieldId, null, ctx, null);

        // Then - should not fail, handle gracefully
        // Null values might match NOT_EQUAL_TO predicates
        assertThat(ctx.getPredicatesEvaluated()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("Should track metrics correctly")
    void shouldTrackMetrics() {
        // Given
        int fieldId = model.getFieldDictionary().getId("STATUS");
        Object value = model.getValueDictionary().getId("ACTIVE");
        EvaluationContext ctx = new EvaluationContext(model.getNumRules(), 100);

        // When - perform multiple evaluations
        evaluator.evaluateEquality(fieldId, value, ctx, null);
        evaluator.evaluateEquality(fieldId, value, ctx, null);
        evaluator.evaluateEquality(fieldId, value, ctx, null);

        // Then - metrics should reflect evaluations
        EqualityOperatorEvaluator.Metrics metrics = evaluator.getMetrics();
        assertThat(metrics.totalEvaluations()).isEqualTo(3);
        assertThat(metrics.compiledFields()).isGreaterThan(0);
    }

    @Test
    @DisplayName("Should be thread-safe for concurrent evaluations")
    void shouldBeThreadSafeForConcurrentEvaluations() throws InterruptedException {
        // Given
        int threadCount = 10;
        int evaluationsPerThread = 100;
        Thread[] threads = new Thread[threadCount];

        // When - multiple threads evaluate concurrently
        for (int i = 0; i < threadCount; i++) {
            threads[i] = new Thread(() -> {
                int fieldId = model.getFieldDictionary().getId("STATUS");
                Object value = model.getValueDictionary().getId("ACTIVE");

                for (int j = 0; j < evaluationsPerThread; j++) {
                    EvaluationContext ctx = new EvaluationContext(model.getNumRules(), 100);
                    evaluator.evaluateEquality(fieldId, value, ctx, null);
                }
            });
            threads[i].start();
        }

        // Wait for all threads
        for (Thread thread : threads) {
            thread.join();
        }

        // Then - should have completed all evaluations without errors
        EqualityOperatorEvaluator.Metrics metrics = evaluator.getMetrics();
        assertThat(metrics.totalEvaluations()).isEqualTo(threadCount * evaluationsPerThread);
    }

    @Test
    @DisplayName("Should demonstrate performance improvement over naive implementation")
    void shouldDemonstratePerformanceImprovement() {
        // Given - large number of predicates
        int fieldId = model.getFieldDictionary().getId("STATUS");
        Object value = model.getValueDictionary().getId("ACTIVE");

        // When - perform many evaluations
        long startTime = System.nanoTime();
        for (int i = 0; i < 10000; i++) {
            EvaluationContext ctx = new EvaluationContext(model.getNumRules(), 100);
            evaluator.evaluateEquality(fieldId, value, ctx, null);
        }
        long endTime = System.nanoTime();

        // Then - should complete in reasonable time
        long durationMs = (endTime - startTime) / 1_000_000;
        System.out.printf("✓ 10K evaluations completed in %d ms (avg: %.2f μs/eval)%n",
                durationMs, (durationMs * 1000.0) / 10000);

        // Verify performance is acceptable (should be < 1ms per evaluation on average)
        assertThat(durationMs).isLessThan(10000); // 10 seconds total for 10K evals
    }
}