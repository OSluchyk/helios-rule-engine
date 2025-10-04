package os.toolset.ruleengine.core;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import os.toolset.ruleengine.core.RuleCompiler.CompilationException;
import os.toolset.ruleengine.model.Event;
import os.toolset.ruleengine.model.MatchResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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

        // Check for updated span names after P0-1 optimization
        assertTrue(spans.stream().anyMatch(s -> s.getName().equals("evaluate-predicates-vectorized")),
                "Should have 'evaluate-predicates-vectorized' span");
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
    void evaluate_shouldHandleMultipleEvaluationsWithSameContext() {
        // P0-2 TEST: Verify context reset and pooling works across multiple evaluations
        Event event1 = new Event("evt-6", "TRANSACTION", Map.of(
                "transaction_amount", 15000,
                "country_code", "US"
        ));

        Event event2 = new Event("evt-7", "TRANSACTION", Map.of(
                "transaction_amount", 500,
                "country_code", "FR",
                "user_age_days", 1
        ));

        Event event3 = new Event("evt-8", "TRANSACTION", Map.of(
                "transaction_amount", 25000,
                "country_code", "GB"
        ));

        // When - evaluate multiple times (should reuse context and pooled objects)
        MatchResult result1 = ruleEvaluator.evaluate(event1);
        MatchResult result2 = ruleEvaluator.evaluate(event2);
        MatchResult result3 = ruleEvaluator.evaluate(event3);

        // Then - all should work correctly
        assertEquals(1, result1.matchedRules().size());
        assertEquals("HIGH_VALUE_TRANSACTION", result1.matchedRules().get(0).ruleCode());

        assertEquals(1, result2.matchedRules().size());
        assertEquals("NEW_USER_ALERT", result2.matchedRules().get(0).ruleCode());

        assertEquals(1, result3.matchedRules().size());
        assertEquals("HIGH_VALUE_TRANSACTION", result3.matchedRules().get(0).ruleCode());

        // Verify metrics show all evaluations
        Map<String, Object> metrics = ruleEvaluator.getMetrics().getSnapshot();
        assertEquals(3L, metrics.get("totalEvaluations"));
    }
}