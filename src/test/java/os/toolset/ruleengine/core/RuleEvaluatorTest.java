package os.toolset.ruleengine.core;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import os.toolset.ruleengine.core.RuleCompiler.CompilationException;
import os.toolset.ruleengine.model.Event;
import os.toolset.ruleengine.model.MatchResult;

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
        ruleEvaluator = new RuleEvaluator(engineModel, tracer);
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

        // Check for the parent evaluation span
        assertTrue(spans.stream().anyMatch(s -> s.getName().equals("rule-evaluation")),
                "Should have 'rule-evaluation' span");

        // Check for updated span names after Phase 4 optimization
        assertTrue(spans.stream().anyMatch(s -> s.getName().equals("evaluate-predicates-weighted")),
                "Should have 'evaluate-predicates-weighted' span");
        assertTrue(spans.stream().anyMatch(s -> s.getName().equals("update-counters-optimized")),
                "Should have 'update-counters-optimized' span");
        assertTrue(spans.stream().anyMatch(s -> s.getName().equals("detect-matches-optimized")),
                "Should have 'detect-matches-optimized' span");

        // Check for attributes in the main span
        SpanData mainSpan = spans.stream()
                .filter(s -> s.getName().equals("rule-evaluation"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No rule-evaluation span found"));

        assertTrue((Long) mainSpan.getAttributes().asMap().get(AttributeKey.longKey("predicatesEvaluated")) > 0,
                "Should have evaluated some predicates");
        assertTrue((Long) mainSpan.getAttributes().asMap().get(AttributeKey.longKey("rulesEvaluated")) > 0,
                "Should have evaluated some rules");
    }

    @Test
    @DisplayName("Should work correctly in concurrent scenarios with ScopedValue")
    void shouldWorkCorrectlyInConcurrentScenariosWithScopedValue() throws InterruptedException {
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
