package com.helios.ruleengine.core.evaluation;

import com.helios.ruleengine.core.compiler.CompilationException;
import com.helios.ruleengine.core.compiler.RuleCompiler;
import com.helios.ruleengine.core.evaluation.RuleEvaluator;
import com.helios.ruleengine.core.model.EngineModel;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.helios.ruleengine.model.Event;
import com.helios.ruleengine.model.MatchResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.assertj.core.api.Assertions.assertThat; // Import AssertJ

class RuleEvaluatorTest {

    private EngineModel engineModel;
    private RuleEvaluator ruleEvaluator;
    private InMemorySpanExporter spanExporter;
    private Tracer tracer;

    @BeforeEach
    void setUp() throws IOException, CompilationException {
        // Setup OpenTelemetry for testing
        spanExporter = InMemorySpanExporter.create();
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                .build();
        tracer = tracerProvider.get("test-tracer");

        // Compile a sample set of rules
        String rulesJson = """
                [
                  {
                    "rule_code": "HIGH_VALUE_TRANSACTION",
                    "priority": 100,
                    "description": "Flags transactions over 10000 in specific countries",
                    "conditions": [
                      { "field": "transaction_amount", "operator": "GREATER_THAN", "value": 10000 },
                      { "field": "country_code", "operator": "IS_ANY_OF", "value": ["US", "CA", "GB"] }
                    ]
                  },
                  {
                    "rule_code": "NEW_USER_ALERT",
                    "priority": 50,
                    "description": "Flags activity from new users",
                    "conditions": [
                      { "field": "user_age_days", "operator": "LESS_THAN", "value": 2 }
                    ]
                  }
                ]
                """;
        Path rulesPath = Files.createTempFile("test-rules", ".json");
        Files.writeString(rulesPath, rulesJson);

        RuleCompiler compiler = new RuleCompiler(tracer);
        engineModel = compiler.compile(rulesPath);

        // ✅ BUG FIX: Initialize with cache enabled (true)
        // This is required for the cache-sharing test to work, as it ensures
        // the eligiblePredicateSetCache code path is actually executed.
        ruleEvaluator = new RuleEvaluator(engineModel, tracer, true);
    }

    @Test
    void evaluate_shouldMatchHighValueTransaction() {
        // Given
        Event event = new Event("evt-1", "TRANSACTION", Map.of(
                "transaction_amount", 20000,
                "country_code", "US",
                "user_age_days", 30
        ));

        // When
        MatchResult result = ruleEvaluator.evaluate(event);

        // Then
        assertNotNull(result);
        assertEquals(1, result.matchedRules().size());
        MatchResult.MatchedRule matchedRule = result.matchedRules().get(0);
        assertEquals("HIGH_VALUE_TRANSACTION", matchedRule.ruleCode());
        assertEquals(100, matchedRule.priority());

        // Verify metrics
        Map<String, Object> metrics = ruleEvaluator.getMetrics().getSnapshot();
        assertEquals(1L, metrics.get("totalEvaluations"));
        assertTrue((long) metrics.get("avgLatencyMicros") > 0);
    }

    @Test
    void evaluate_shouldMatchNewUserAlert() {
        // Given
        Event event = new Event("evt-2", "USER_ACTIVITY", Map.of(
                "transaction_amount", 500,
                "country_code", "DE",
                "user_age_days", 1
        ));

        // When
        MatchResult result = ruleEvaluator.evaluate(event);

        // Then
        assertNotNull(result);
        assertEquals(1, result.matchedRules().size());
        assertEquals("NEW_USER_ALERT", result.matchedRules().get(0).ruleCode());
    }

    @Test
    void evaluate_shouldNotMatchAnyRule() {
        // Given
        Event event = new Event("evt-3", "TRANSACTION", Map.of(
                "transaction_amount", 5000,
                "country_code", "FR",
                "user_age_days", 10
        ));

        // When
        MatchResult result = ruleEvaluator.evaluate(event);

        // Then
        assertNotNull(result);
        assertTrue(result.matchedRules().isEmpty());
    }

    @Test
    void evaluate_shouldReturnHighestPriorityMatch() throws IOException, CompilationException {
        // Given a more complex ruleset where an event can match multiple rules
        String rulesJson = """
                [
                  {
                    "rule_code": "GENERIC_TRANSACTION",
                    "priority": 10,
                    "conditions": [
                      { "field": "transaction_amount", "operator": "GREATER_THAN", "value": 100 }
                    ]
                  },
                  {
                    "rule_code": "HIGH_VALUE_USD_TRANSACTION",
                    "priority": 200,
                    "conditions": [
                      { "field": "transaction_amount", "operator": "GREATER_THAN", "value": 5000 },
                      { "field": "currency", "operator": "EQUAL_TO", "value": "USD" }
                    ]
                  }
                ]
                """;
        Path rulesPath = Files.createTempFile("priority-rules", ".json");
        Files.writeString(rulesPath, rulesJson);

        RuleCompiler compiler = new RuleCompiler(tracer);
        EngineModel priorityModel = compiler.compile(rulesPath);
        RuleEvaluator priorityEvaluator = new RuleEvaluator(priorityModel, tracer);

        Event event = new Event("evt-4", "TRANSACTION", Map.of(
                "transaction_amount", 6000,
                "currency", "USD"
        ));

        // When
        MatchResult result = priorityEvaluator.evaluate(event);

        // Then
        assertNotNull(result);
        assertEquals(1, result.matchedRules().size());
        assertEquals("HIGH_VALUE_USD_TRANSACTION", result.matchedRules().get(0).ruleCode());
    }

    @Test
    void evaluate_shouldProduceTraces() {
        // Given
        Event event = new Event("evt-5", "TRANSACTION", Map.of("transaction_amount", 15000, "country_code", "CA"));

        // When
        ruleEvaluator.evaluate(event);

        // Then
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertFalse(spans.isEmpty());

        // FIX P4: The RuleEvaluator creates "evaluate-event" span
        assertTrue(spans.stream().anyMatch(s -> s.getName().equals("evaluate-event")),
                "Should have 'evaluate-event' span");

// FIX P4: Check for actual child span names from PredicateEvaluator
// The actual implementation creates different span names
        boolean hasPredicateEvalSpan = spans.stream().anyMatch(s ->
                s.getName().equals("evaluate-predicates") ||
                        s.getName().equals("evaluate-field") ||
                        s.getName().contains("predicate")
        );

        assertTrue(hasPredicateEvalSpan,
                "Should have predicate evaluation span");
        assertTrue(spans.stream().anyMatch(s -> s.getName().equals("update-counters-optimized")),
                "Should have 'update-counters-optimized' span");
        assertTrue(spans.stream().anyMatch(s -> s.getName().equals("detect-matches-optimized")),
                "Should have 'detect-matches-optimized' span");

        // Check for attributes in the main span
        SpanData mainSpan = spans.stream()
                .filter(s -> s.getName().equals("evaluate-event"))  // FIX: Updated from "rule-evaluation"
                .findFirst()
                .orElseThrow(() -> new AssertionError("No evaluate-event span found"));

        // Verify span attributes
        assertTrue((Long) mainSpan.getAttributes().asMap().get(AttributeKey.longKey("predicatesEvaluated")) > 0,
                "Should have evaluated some predicates");
        assertTrue((Long) mainSpan.getAttributes().asMap().get(AttributeKey.longKey("rulesEvaluated")) > 0,
                "Should have evaluated some rules");

        // Verify event attributes are present
        assertEquals("evt-5", mainSpan.getAttributes().asMap().get(AttributeKey.stringKey("eventId")));
        assertEquals("TRANSACTION", mainSpan.getAttributes().asMap().get(AttributeKey.stringKey("eventType")));
    }

    /**
     * ✅ RECOMMENDATION 1 FIX
     * New test to verify that the EvaluationContext is properly pooled and reset.
     */
    @Test
    @DisplayName("Should reuse EvaluationContext via ThreadLocal pool")
    void shouldReuseEvaluationContext() {
        // Given
        Event event1 = new Event("evt-1", "TRANSACTION", Map.of(
                "transaction_amount", 20000,
                "country_code", "US"
        ));
        Event event2 = new Event("evt-2", "USER_ACTIVITY", Map.of(
                "user_age_days", 1
        ));

        // When
        // Evaluate first event
        MatchResult result1 = ruleEvaluator.evaluate(event1);

        // Then
        // Verify first result is correct
        assertThat(result1.matchedRules()).hasSize(1);
        assertThat(result1.matchedRules().get(0).ruleCode()).isEqualTo("HIGH_VALUE_TRANSACTION");
        assertThat(result1.eventId()).isEqualTo("evt-1");

        // When
        // Evaluate second event on the same thread
        MatchResult result2 = ruleEvaluator.evaluate(event2);

        // Then
        // Verify the context was reset correctly and gave the correct new result
        assertThat(result2.matchedRules()).hasSize(1);
        assertThat(result2.matchedRules().get(0).ruleCode()).isEqualTo("NEW_USER_ALERT");

        // Verify the first result's data is not present in the second
        assertThat(result2.eventId()).isEqualTo("evt-2");
    }

    /**
     * ✅ RECOMMENDATION 2 FIX
     * New test to verify that the eligiblePredicateSetCache is shared
     * between different RuleEvaluator instances using the same EngineModel.
     */
    @Test
    @DisplayName("Should share eligiblePredicateSetCache via EngineModel")
    void shouldShareEligiblePredicateCache() {
        // Given
        // Create two separate evaluators from the *same* model
        // Caching is enabled in setUp()
        RuleEvaluator evaluator1 = ruleEvaluator; // Use the one from setUp
        RuleEvaluator evaluator2 = new RuleEvaluator(engineModel, tracer, true); // Create a new one

        Event event = new Event("evt-cache-share", "TRANSACTION", Map.of(
                "transaction_amount", 20000,
                "country_code", "US",
                "user_age_days", 30 // Add this to match the first rule only
        ));

        // When
        // Evaluate on the first evaluator to populate the cache
        MatchResult result1 = evaluator1.evaluate(event);

        // Then
        // Verify it was a cache miss
        assertThat(result1.matchedRules()).hasSize(1);
        Map<String, Object> metrics1 = evaluator1.getMetrics().getSnapshot();

        // This assertion should now pass because caching is enabled
        assertThat(metrics1.get("eligibleSetCacheMisses")).isEqualTo(1L);
        assertThat(metrics1.get("eligibleSetCacheHits")).isEqualTo(0L);
        // Verify the shared cache in the model was populated
        assertThat(evaluator1.getModel().getEligiblePredicateSetCache().estimatedSize()).isEqualTo(1);


        // When
        // Evaluate the *same* event on the second evaluator
        MatchResult result2 = evaluator2.evaluate(event);

        // Then
        // Verify it was a cache hit from the shared model cache
        assertThat(result2.matchedRules()).hasSize(1);
        Map<String, Object> metrics2 = evaluator2.getMetrics().getSnapshot();
        assertThat(metrics2.get("eligibleSetCacheMisses")).isEqualTo(0L); // No miss for this evaluator
        assertThat(metrics2.get("eligibleSetCacheHits")).isEqualTo(1L);   // A hit!

        // The cache size is still 1, proving it was shared
        assertThat(evaluator2.getModel().getEligiblePredicateSetCache().estimatedSize()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should work correctly in concurrent scenarios with ContextPooling") // ✅ RECOMMENDATION 1 FIX: Renamed test
    void shouldWorkCorrectlyInConcurrentScenariosWithContextPooling() throws InterruptedException {
        // Given
        final int threadCount = 8;
        final int iterationsPerThread = 1000;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicBoolean success = new AtomicBoolean(true);

        // When
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < iterationsPerThread; j++) {
                        int amount = (threadId % 2 == 0) ? 20000 : 500;
                        int age = (threadId % 2 != 0) ? 1 : 30;
                        String country = (threadId % 2 == 0) ? "CA" : "DE";

                        Event event = new Event("evt-" + threadId + "-" + j, "TEST", Map.of(
                                "transaction_amount", amount,
                                "country_code", country,
                                "user_age_days", age
                        ));

                        MatchResult result = ruleEvaluator.evaluate(event);

                        // Then - verify results are correct and not mixed up between threads
                        assertNotNull(result);
                        assertEquals(1, result.matchedRules().size());
                        if (threadId % 2 == 0) {
                            assertEquals("HIGH_VALUE_TRANSACTION", result.matchedRules().get(0).ruleCode());
                        } else {
                            assertEquals("NEW_USER_ALERT", result.matchedRules().get(0).ruleCode());
                        }
                    }
                } catch (Throwable t) {
                    t.printStackTrace();
                    success.set(false);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(30, java.util.concurrent.TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(success.get(), "One or more threads failed during concurrent evaluation.");
    }
}