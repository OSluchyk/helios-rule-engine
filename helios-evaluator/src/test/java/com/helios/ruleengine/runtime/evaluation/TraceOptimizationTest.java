package com.helios.ruleengine.runtime.evaluation;

import com.helios.ruleengine.api.model.Event;
import com.helios.ruleengine.api.model.EvaluationResult;
import com.helios.ruleengine.api.model.EvaluationTrace;
import com.helios.ruleengine.api.model.TraceLevel;
import com.helios.ruleengine.compiler.RuleCompiler;
import com.helios.ruleengine.runtime.model.EngineModel;
import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TraceOptimizationTest {

    private RuleEvaluator evaluator;

    @BeforeEach
    void setUp() throws Exception {
        String rulesJson = """
                [
                  {
                    "rule_code": "MATCHING_RULE",
                    "priority": 100,
                    "conditions": [
                      {"field": "status", "operator": "EQUAL_TO", "value": "ACTIVE"},
                      {"field": "amount", "operator": "GREATER_THAN", "value": 100}
                    ]
                  },
                  {
                    "rule_code": "NON_MATCHING_RULE",
                    "priority": 50,
                    "conditions": [
                       {"field": "status", "operator": "EQUAL_TO", "value": "PENDING"}
                    ]
                  }
                ]
                """;

        Path rulesFile = Files.createTempFile("trace_rules", ".json");
        Files.writeString(rulesFile, rulesJson);

        RuleCompiler compiler = new RuleCompiler(OpenTelemetry.noop().getTracer("test"));
        EngineModel model = compiler.compile(rulesFile);
        evaluator = new RuleEvaluator(model, OpenTelemetry.noop().getTracer("test"), false);

        Files.delete(rulesFile);
    }

    @Test
    @DisplayName("FULL level should capture match, predicates and values")
    void testFullLevel() {
        Event event = new Event("evt-1", "TEST", Map.of("status", "ACTIVE", "amount", 150));

        EvaluationResult result = evaluator.evaluateWithTrace(event, TraceLevel.FULL, false);

        assertThat(result.matchResult().matchedRules()).isNotEmpty();
        EvaluationTrace trace = result.trace();
        assertThat(trace).isNotNull();

        // Check rule details
        assertThat(trace.ruleDetails()).isNotEmpty();

        // Check predicate outcomes
        assertThat(trace.predicateOutcomes()).isNotEmpty();

        // Check values (FULL level)
        boolean hasValues = trace.predicateOutcomes().stream()
                .anyMatch(p -> p.actualValue() != null);
        assertThat(hasValues).isTrue();
    }

    @Test
    @DisplayName("STANDARD level should capture predicates but NO values")
    void testStandardLevel() {
        Event event = new Event("evt-1", "TEST", Map.of("status", "ACTIVE", "amount", 150));

        EvaluationResult result = evaluator.evaluateWithTrace(event, TraceLevel.STANDARD, false);

        assertThat(result.matchResult().matchedRules()).isNotEmpty();
        EvaluationTrace trace = result.trace();
        assertThat(trace).isNotNull();

        // Check rule details
        assertThat(trace.ruleDetails()).isNotEmpty();

        // Check predicate outcomes
        assertThat(trace.predicateOutcomes()).isNotEmpty();

        // Check values (STANDARD level should have predicates but minimal overhead,
        // usually that implies not having the copied values if they weren't matched?
        // Wait, TraceCollector logic says for STANDARD we skip encodedAttributesCopy.
        // buildPredicateOutcomes uses encodedAttributesCopy to look up actualValue.
        // If encodedAttributesCopy is empty, actualValue will be null (or default).
        // Let's verify.)

        // In current RuleEvaluator logic:
        // predicate.value() (expected) is from model.
        // actualValue comes from encodedAttributes.

        boolean hasValues = trace.predicateOutcomes().stream()
                .anyMatch(p -> p.actualValue() != null);
        assertThat(hasValues).as("STANDARD level should not capture actual values").isFalse();
    }

    @Test
    @DisplayName("BASIC level should capture rule details but NO predicates")
    void testBasicLevel() {
        Event event = new Event("evt-1", "TEST", Map.of("status", "ACTIVE", "amount", 150));

        EvaluationResult result = evaluator.evaluateWithTrace(event, TraceLevel.BASIC, false);

        assertThat(result.matchResult().matchedRules()).isNotEmpty();
        EvaluationTrace trace = result.trace();
        assertThat(trace).isNotNull();

        // Check rule details
        assertThat(trace.ruleDetails()).isNotEmpty();

        // Check predicate outcomes (Should be empty for BASIC)
        assertThat(trace.predicateOutcomes()).isEmpty();
    }

    @Test
    @DisplayName("Conditional Tracing - Match Found -> Full Trace")
    void testConditionalMatch() {
        Event event = new Event("evt-match", "TEST", Map.of("status", "ACTIVE", "amount", 150));

        // Conditional = true. Should capture trace because rule matches.
        EvaluationResult result = evaluator.evaluateWithTrace(event, TraceLevel.FULL, true);

        assertThat(result.matchResult().matchedRules()).isNotEmpty();
        EvaluationTrace trace = result.trace();

        assertThat(trace.ruleDetails()).isNotEmpty();
        assertThat(trace.predicateOutcomes()).isNotEmpty();
    }

    @Test
    @DisplayName("Conditional Tracing - No Match -> Empty/Minimal Trace")
    void testConditionalNoMatch() {
        // Event matches NO rules (amount too low)
        Event event = new Event("evt-no-match", "TEST", Map.of("status", "ACTIVE", "amount", 50));

        // Conditional = true. Should NOT capture predicate snapshot.
        EvaluationResult result = evaluator.evaluateWithTrace(event, TraceLevel.FULL, true);

        assertThat(result.matchResult().matchedRules()).isEmpty();
        EvaluationTrace trace = result.trace();

        // Rule details might be present (for touched rules) if we didn't suppress them
        // in detectMatches?
        // Wait, in detectMatchesOptimized I added 'if (tracing && collector != null)'.
        // Inside that block, I added 'collector.notifyMatch()'.
        // But do I suppress 'addRuleDetail' if conditional is true?
        // No. I didn't add logic to suppress 'addRuleDetail'.
        // So rule details WILL be present.
        // BUT predicate outcomes will be EMPTY because capturePredicateSnapshot
        // returned early without doing anything.

        assertThat(trace.predicateOutcomes()).isEmpty();

        // Rule details for touched rules are still useful
        assertThat(trace.ruleDetails()).isNotEmpty();
    }
}
