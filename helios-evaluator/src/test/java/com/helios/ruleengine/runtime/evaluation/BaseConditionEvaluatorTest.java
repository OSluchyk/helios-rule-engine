package com.helios.ruleengine.runtime.evaluation;

import com.helios.ruleengine.api.exceptions.CompilationException;
import com.helios.ruleengine.compiler.RuleCompiler;
import com.helios.ruleengine.runtime.context.EventEncoder;
import com.helios.ruleengine.runtime.evaluation.BaseConditionEvaluator;
import com.helios.ruleengine.runtime.model.EngineModel;

import io.opentelemetry.api.trace.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.helios.ruleengine.api.model.Event;
import com.helios.ruleengine.cache.NoOpCache;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class BaseConditionEvaluatorTest {

    private Tracer tracer;
    private Path rulesDir;

    @BeforeEach
    void setUp() throws IOException {
        tracer = io.opentelemetry.api.OpenTelemetry.noop().getTracer("test");
        rulesDir = Files.createTempDirectory("base-cond-test-");
    }

    private EngineModel compileRules(String rulesJson) throws IOException, CompilationException {
        Path rulesFile = rulesDir.resolve("rules.json");
        Files.writeString(rulesFile, rulesJson);
        RuleCompiler compiler = new RuleCompiler(tracer);
        return compiler.compile(rulesFile);
    }

    @Test
    @DisplayName("Should cache results for static predicates (EQUAL_TO)")
    void shouldCacheStaticPredicates() throws IOException, CompilationException, ExecutionException, InterruptedException {
        // Given
        String rulesJson = """
        [
          {
            "rule_code": "STATIC_RULE",
            "conditions": [
              { "field": "status", "operator": "EQUAL_TO", "value": "ACTIVE" },
              { "field": "amount", "operator": "GREATER_THAN", "value": 100 }
            ]
          }
        ]
        """;
        EngineModel model = compileRules(rulesJson);
        BaseConditionEvaluator evaluator = new BaseConditionEvaluator(model, new NoOpCache());

        // FIX: Create EventEncoder for the evaluator
        EventEncoder encoder = new EventEncoder(model.getFieldDictionary(), model.getValueDictionary());

        Event event = new Event("evt-1", "TEST", Map.of(
                "status", "ACTIVE",
                "amount", 150
        ));

        // When
        // FIX: Pass EventEncoder as second parameter
        BaseConditionEvaluator.EvaluationResult result = evaluator.evaluateBaseConditions(event, encoder).get();

        // Then
        // 'status == ACTIVE' is a static predicate and is evaluated.
        // 'amount > 100' is dynamic and is NOT evaluated.
        // The rule is eligible because its static part is a match.
        assertThat(result.getCardinality()).isEqualTo(1);
        assertThat(result.predicatesEvaluated).isEqualTo(1);

        // Check metrics
        Map<String, Object> metrics = evaluator.getMetrics();
        assertThat((Integer) metrics.get("baseConditionSets")).isEqualTo(1); // One base set: { status == ACTIVE }
        assertThat((Integer) metrics.get("rulesWithNoBaseConditions")).isEqualTo(0);
    }

    @Test
    @DisplayName("Should correctly identify rules with no static predicates")
    void shouldHandleRulesWithNoStaticPredicates() throws IOException, CompilationException, ExecutionException, InterruptedException {
        // Given
        String rulesJson = """
        [
          {
            "rule_code": "DYNAMIC_RULE_ONLY",
            "conditions": [
              { "field": "amount", "operator": "GREATER_THAN", "value": 100 }
            ]
          }
        ]
        """;
        EngineModel model = compileRules(rulesJson);
        BaseConditionEvaluator evaluator = new BaseConditionEvaluator(model, new NoOpCache());

        // FIX: Create EventEncoder for the evaluator
        EventEncoder encoder = new EventEncoder(model.getFieldDictionary(), model.getValueDictionary());

        Event event = new Event("evt-1", "TEST", Map.of("amount", 150));

        // When
        // FIX: Pass EventEncoder as second parameter
        BaseConditionEvaluator.EvaluationResult result = evaluator.evaluateBaseConditions(event, encoder).get();

        // Then
        // No static predicates are evaluated
        assertThat(result.predicatesEvaluated).isEqualTo(0);

        // The rule is still eligible because it has no base conditions to fail
        assertThat(result.getCardinality()).isEqualTo(1);

        // Check metrics
        Map<String, Object> metrics = evaluator.getMetrics();
        assertThat((Integer) metrics.get("baseConditionSets")).isEqualTo(0);
        // This rule is correctly identified as having no static base conditions
        assertThat((Integer) metrics.get("rulesWithNoBaseConditions")).isEqualTo(1);
    }

    /**
     * This test verifies that the IS_ANY_OF operator is correctly expanded
     * by the RuleCompiler and the resulting EQUAL_TO predicates are
     * cached by the BaseConditionEvaluator.
     */
    @Test
    @DisplayName("Should achieve subset factoring by caching expanded IS_ANY_OF predicates")
    void shouldCacheExpandedIsAnyOf() throws IOException, CompilationException, ExecutionException, InterruptedException {
        // Given
        String rulesJson = """
        [
          {
            "rule_code": "RULE_1_US_CA",
            "conditions": [
              { "field": "country", "operator": "IS_ANY_OF", "value": ["US", "CA"] },
              { "field": "amount", "operator": "GREATER_THAN", "value": 100 }
            ]
          },
          {
            "rule_code": "RULE_2_US_MX",
            "conditions": [
              { "field": "country", "operator": "IS_ANY_OF", "value": ["US", "MX"] },
              { "field": "amount", "operator": "LESS_THAN", "value": 50 }
            ]
          }
        ]
        """;
        // This compile step now performs DNF expansion on IS_ANY_OF
        // It creates 4 combinations:
        // 1. { country == US, amount > 100 }  (RULE_1_US_CA)
        // 2. { country == CA, amount > 100 }  (RULE_1_US_CA)
        // 3. { country == US, amount < 50 }   (RULE_2_US_MX)
        // 4. { country == MX, amount < 50 }   (RULE_2_US_MX)
        EngineModel model = compileRules(rulesJson);

        // The BaseConditionEvaluator will find 3 static base condition sets:
        // Set 1: { country == US }  (Used by combo 1, 3)
        // Set 2: { country == CA }  (Used by combo 2)
        // Set 3: { country == MX }  (Used by combo 4)
        BaseConditionEvaluator evaluator = new BaseConditionEvaluator(model, new NoOpCache());

        // FIX: Create EventEncoder for the evaluator
        EventEncoder encoder = new EventEncoder(model.getFieldDictionary(), model.getValueDictionary());

        // This event matches { country == US }
        Event event = new Event("evt-1", "TEST", Map.of(
                "country", "US",
                "amount", 150
        ));

        // When
        // FIX: Pass EventEncoder as second parameter
        BaseConditionEvaluator.EvaluationResult result = evaluator.evaluateBaseConditions(event, encoder).get();

        // Then
        // The evaluator must check all 3 static sets ({US}, {CA}, {MX})
        // because the event provides the 'country' field, making all 3 "applicable".
        // It evaluates "US" == "US" (true), "US" == "CA" (false), "US" == "MX" (false).
        assertThat(result.predicatesEvaluated).isEqualTo(3);

        // The evaluator finds the { country == US } base set matches.
        // It returns the two combinations associated with that set (combo 1, 3).
        assertThat(result.getCardinality()).isEqualTo(2); // Combos 1 and 3 are eligible

        // Verify the internal state (this is the "subset factoring")
        Map<String, Object> metrics = evaluator.getMetrics();
        assertThat((Integer) metrics.get("totalCombinations")).isEqualTo(4);
        assertThat((Integer) metrics.get("baseConditionSets")).isEqualTo(3); // {US}, {CA}, {MX}
        assertThat((Integer) metrics.get("rulesWithNoBaseConditions")).isEqualTo(0);
        assertThat((Double) metrics.get("baseConditionReductionPercent")).isBetween(24.0, 26.0); // 1 - 3/4 = 25%
    }

    /**
     * This test verifies that the newly added NOT_EQUAL_TO operator
     * is correctly treated as a "static" predicate and is cached.
     */
    @Test
    @DisplayName("Should handle NOT_EQUAL_TO as a static, cacheable predicate")
    void shouldHandleNotEqualToAsStatic() throws IOException, CompilationException, ExecutionException, InterruptedException {
        // Given
        String rulesJson = """
        [
          {
            "rule_code": "NOT_US_RULE",
            "conditions": [
              { "field": "country", "operator": "NOT_EQUAL_TO", "value": "US" },
              { "field": "amount", "operator": "GREATER_THAN", "value": 100 }
            ]
          },
          {
            "rule_code": "IS_US_RULE",
            "conditions": [
              { "field": "country", "operator": "EQUAL_TO", "value": "US" },
              { "field": "amount", "operator": "GREATER_THAN", "value": 100 }
            ]
          }
        ]
        """;
        // This creates 2 combinations:
        // 1. { country != US, amount > 100 }
        // 2. { country == US, amount > 100 }
        EngineModel model = compileRules(rulesJson);

        // The BaseConditionEvaluator will find 2 static base condition sets:
        // Set 1: { country != US }
        // Set 2: { country == US }
        BaseConditionEvaluator evaluator = new BaseConditionEvaluator(model, new NoOpCache());

        // FIX: Create EventEncoder for the evaluator
        EventEncoder encoder = new EventEncoder(model.getFieldDictionary(), model.getValueDictionary());

        // This event matches { country != US }
        Event event = new Event("evt-1", "TEST", Map.of(
                "country", "CA", // CA is not US
                "amount", 150
        ));

        // When
        // FIX: Pass EventEncoder as second parameter
        BaseConditionEvaluator.EvaluationResult result = evaluator.evaluateBaseConditions(event, encoder).get();

        // Then
        // The evaluator checks both { country != US } and { country == US }.
        // { country != US } matches.
        // { country == US } does not match.
        // It returns the one combination associated with the matching set.
        assertThat(result.predicatesEvaluated).isEqualTo(2);
        assertThat(result.getCardinality()).isEqualTo(1); // Only the NOT_US_RULE combination is eligible

        // Check metrics
        Map<String, Object> metrics = evaluator.getMetrics();
        assertThat((Integer) metrics.get("baseConditionSets")).isEqualTo(2);
        assertThat((Integer) metrics.get("rulesWithNoBaseConditions")).isEqualTo(0);
    }
}