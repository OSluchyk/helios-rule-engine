package com.helios.ruleengine.runtime.evaluation;

import com.helios.ruleengine.api.exceptions.CompilationException;
import com.helios.ruleengine.api.model.Event;
import com.helios.ruleengine.api.model.MatchResult;
import com.helios.ruleengine.compiler.RuleCompiler;
import com.helios.ruleengine.runtime.model.EngineModel;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuleEvaluatorComplexTest {

    private RuleEvaluator ruleEvaluator;
    private Tracer tracer;

    @BeforeEach
    void setUp() throws IOException, CompilationException {
        tracer = OpenTelemetry.noop().getTracer("test");

        String rulesJson = """
                [
                  {
                    "rule_code": "REGEX_RULE",
                    "conditions": [
                      { "field": "email", "operator": "REGEX", "value": ".*@company\\\\.com" }
                    ]
                  },
                  {
                    "rule_code": "BETWEEN_RULE",
                    "conditions": [
                      { "field": "age", "operator": "BETWEEN", "value": [18, 65] }
                    ]
                  },
                  {
                    "rule_code": "IS_NONE_OF_RULE",
                    "conditions": [
                      { "field": "status", "operator": "IS_NONE_OF", "value": ["BLOCKED", "SUSPENDED"] }
                    ]
                  }
                ]
                """;
        Path rulesPath = Files.createTempFile("complex-rules", ".json");
        Files.writeString(rulesPath, rulesJson);

        RuleCompiler compiler = new RuleCompiler(tracer);
        EngineModel engineModel = compiler.compile(rulesPath);
        ruleEvaluator = new RuleEvaluator(engineModel, tracer, false);
    }

    @Test
    @DisplayName("Should match REGEX condition")
    void shouldMatchRegex() {
        Event matchEvent = new Event("e1", "TEST", Map.of("email", "user@company.com"));
        MatchResult result = ruleEvaluator.evaluate(matchEvent);
        assertThat(result.matchedRules()).hasSize(1);
        assertThat(result.matchedRules().get(0).ruleCode()).isEqualTo("REGEX_RULE");

        Event noMatchEvent = new Event("e2", "TEST", Map.of("email", "user@other.com"));
        MatchResult result2 = ruleEvaluator.evaluate(noMatchEvent);
        assertThat(result2.matchedRules()).isEmpty();
    }

    @Test
    @DisplayName("Should match BETWEEN condition")
    void shouldMatchBetween() {
        Event matchEvent = new Event("e1", "TEST", Map.of("age", 30));
        MatchResult result = ruleEvaluator.evaluate(matchEvent);
        assertThat(result.matchedRules()).hasSize(1);
        assertThat(result.matchedRules().get(0).ruleCode()).isEqualTo("BETWEEN_RULE");

        Event lowerBound = new Event("e2", "TEST", Map.of("age", 18));
        assertThat(ruleEvaluator.evaluate(lowerBound).matchedRules()).hasSize(1);

        Event upperBound = new Event("e3", "TEST", Map.of("age", 65));
        assertThat(ruleEvaluator.evaluate(upperBound).matchedRules()).hasSize(1);

        Event tooYoung = new Event("e4", "TEST", Map.of("age", 17));
        assertThat(ruleEvaluator.evaluate(tooYoung).matchedRules()).isEmpty();

        Event tooOld = new Event("e5", "TEST", Map.of("age", 66));
        assertThat(ruleEvaluator.evaluate(tooOld).matchedRules()).isEmpty();
    }

    @Test
    @DisplayName("Should match IS_NONE_OF condition")
    void shouldMatchIsNoneOf() {
        Event matchEvent = new Event("e1", "TEST", Map.of("status", "ACTIVE"));
        MatchResult result = ruleEvaluator.evaluate(matchEvent);
        assertThat(result.matchedRules()).hasSize(1);
        assertThat(result.matchedRules().get(0).ruleCode()).isEqualTo("IS_NONE_OF_RULE");

        Event blockEvent = new Event("e2", "TEST", Map.of("status", "BLOCKED"));
        assertThat(ruleEvaluator.evaluate(blockEvent).matchedRules()).isEmpty();

        Event suspendEvent = new Event("e3", "TEST", Map.of("status", "SUSPENDED"));
        assertThat(ruleEvaluator.evaluate(suspendEvent).matchedRules()).isEmpty();
    }
}
