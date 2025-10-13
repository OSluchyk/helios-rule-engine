package com.helios.ruleengine.core.evaluation;

import com.helios.ruleengine.core.compiler.RuleCompiler;
import com.helios.ruleengine.core.model.EngineModel;
import com.helios.ruleengine.infrastructure.telemetry.TracingService;
import com.helios.ruleengine.model.Event;
import com.helios.ruleengine.model.MatchResult;
import com.helios.ruleengine.core.evaluation.RuleEvaluator;
import io.opentelemetry.api.trace.Tracer;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REGRESSION TEST: Ensure rules with numeric operators are NOT silently dropped
 *
 * BUG: isStaticPredicate() only considered EQUAL_TO and IS_ANY_OF as static,
 *      causing rules with GREATER_THAN, LESS_THAN, BETWEEN to be excluded
 *      from base condition evaluation.
 *
 * FIX: Include numeric operators in isStaticPredicate() definition.
 */
@DisplayName("Numeric Predicate Evaluation - Regression Test")
class NumericPredicateEvaluationTest {

    private static final Tracer NOOP_TRACER = TracingService.getInstance().getTracer();
    private static Path tempDir;

    @BeforeAll
    static void beforeAll() throws Exception {
        tempDir = Files.createTempDirectory("numeric_test");
    }

    @AfterAll
    static void afterAll() throws Exception {
        Files.walk(tempDir)
                .sorted(java.util.Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(java.io.File::delete);
    }

    @Test
    @DisplayName("CRITICAL: Rules with numeric operators must NOT be dropped")
    void shouldEvaluateRulesWithNumericOperators() throws Exception {
        // Given - Rules with ONLY numeric operators
        Path rulesFile = tempDir.resolve("numeric_rules.json");
        Files.writeString(rulesFile, """
        [
          {
            "rule_code": "SMALL_AMOUNT",
            "priority": 10,
            "conditions": [
              {"field": "AMOUNT", "operator": "LESS_THAN", "value": 1000}
            ]
          },
          {
            "rule_code": "MEDIUM_AMOUNT",
            "priority": 50,
            "conditions": [
              {"field": "AMOUNT", "operator": "GREATER_THAN", "value": 1000}
            ]
          },
          {
            "rule_code": "LARGE_AMOUNT",
            "priority": 100,
            "conditions": [
              {"field": "AMOUNT", "operator": "GREATER_THAN", "value": 5000}
            ]
          }
        ]
        """);

        RuleCompiler compiler = new RuleCompiler(NOOP_TRACER);
        EngineModel model = compiler.compile(rulesFile, EngineModel.SelectionStrategy.ALL_MATCHES);
        RuleEvaluator evaluator = new RuleEvaluator(model, NOOP_TRACER, true);

        // When - Evaluate event with amount = 7500
        Event event = new Event("evt-1", "TEST", Map.of("amount", 7500));
        MatchResult result = evaluator.evaluate(event);

        // Then - Should match MEDIUM_AMOUNT and LARGE_AMOUNT (NOT SMALL_AMOUNT)
        assertThat(result.matchedRules())
                .as("Rules with numeric operators MUST be evaluated")
                .hasSize(2);

        List<String> matchedCodes = result.matchedRules().stream()
                .map(m -> m.ruleCode())
                .collect(Collectors.toList());

        assertThat(matchedCodes)
                .as("Should match rules where amount > 1000 AND amount > 5000")
                .containsExactlyInAnyOrder("MEDIUM_AMOUNT", "LARGE_AMOUNT");

        System.out.printf("✅ PASS: Matched %d numeric rules: %s%n",
                result.matchedRules().size(), matchedCodes);
    }

    @Test
    @DisplayName("Should evaluate mixed static and numeric predicates")
    void shouldEvaluateMixedPredicates() throws Exception {
        // Given - Rules mixing EQUAL_TO and numeric operators
        Path rulesFile = tempDir.resolve("mixed_rules.json");
        Files.writeString(rulesFile, """
        [
          {
            "rule_code": "LARGE_AMOUNT",
            "priority": 100,
            "conditions": [
              {"field": "AMOUNT", "operator": "GREATER_THAN", "value": 5000}
            ]
          },
          {
            "rule_code": "ACTIVE_STATUS",
            "priority": 60,
            "conditions": [
              {"field": "STATUS", "operator": "EQUAL_TO", "value": "ACTIVE"}
            ]
          },
          {
            "rule_code": "HIGH_PRIORITY",
            "priority": 90,
            "conditions": [
              {"field": "PRIORITY", "operator": "EQUAL_TO", "value": "HIGH"}
            ]
          },
          {
            "rule_code": "MEDIUM_AMOUNT",
            "priority": 50,
            "conditions": [
              {"field": "AMOUNT", "operator": "GREATER_THAN", "value": 1000}
            ]
          }
        ]
        """);

        RuleCompiler compiler = new RuleCompiler(NOOP_TRACER);
        EngineModel model = compiler.compile(rulesFile, EngineModel.SelectionStrategy.ALL_MATCHES);
        RuleEvaluator evaluator = new RuleEvaluator(model, NOOP_TRACER, true);

        // When - Evaluate event matching all conditions
        Event event = new Event("evt-mixed", "TEST", Map.of(
                "amount", 7500,
                "status", "ACTIVE",
                "priority", "HIGH"
        ));

        MatchResult result = evaluator.evaluate(event);

        // Then - ALL 4 rules should match
        assertThat(result.matchedRules())
                .as("All matching rules must be evaluated (numeric + equality)")
                .hasSize(4);

        List<String> matchedCodes = result.matchedRules().stream()
                .map(m -> m.ruleCode())
                .collect(Collectors.toList());

        assertThat(matchedCodes)
                .as("Should match all rules with satisfied conditions")
                .containsExactlyInAnyOrder(
                        "LARGE_AMOUNT",    // amount > 5000
                        "MEDIUM_AMOUNT",   // amount > 1000
                        "ACTIVE_STATUS",   // status == ACTIVE
                        "HIGH_PRIORITY"    // priority == HIGH
                );

        System.out.printf("✅ PASS: Correctly matched all %d rules: %s%n",
                result.matchedRules().size(), matchedCodes);
    }

    @Test
    @DisplayName("Should handle BETWEEN operator correctly")
    void shouldHandleBetweenOperator() throws Exception {
        // Given - Rules with BETWEEN operator
        Path rulesFile = tempDir.resolve("between_rules.json");
        Files.writeString(rulesFile, """
        [
          {
            "rule_code": "MID_RANGE",
            "priority": 50,
            "conditions": [
              {"field": "AMOUNT", "operator": "BETWEEN", "value": [1000, 10000]}
            ]
          },
          {
            "rule_code": "HIGH_RANGE",
            "priority": 80,
            "conditions": [
              {"field": "AMOUNT", "operator": "BETWEEN", "value": [5000, 20000]}
            ]
          }
        ]
        """);

        RuleCompiler compiler = new RuleCompiler(NOOP_TRACER);
        EngineModel model = compiler.compile(rulesFile, EngineModel.SelectionStrategy.ALL_MATCHES);
        RuleEvaluator evaluator = new RuleEvaluator(model, NOOP_TRACER, true);

        // When - Evaluate event with amount = 7500
        Event event = new Event("evt-between", "TEST", Map.of("amount", 7500));
        MatchResult result = evaluator.evaluate(event);

        // Then - Should match both MID_RANGE and HIGH_RANGE
        assertThat(result.matchedRules())
                .as("BETWEEN operator must be evaluated")
                .hasSize(2);

        List<String> matchedCodes = result.matchedRules().stream()
                .map(m -> m.ruleCode())
                .collect(Collectors.toList());

        assertThat(matchedCodes)
                .containsExactlyInAnyOrder("MID_RANGE", "HIGH_RANGE");

        System.out.printf("✅ PASS: BETWEEN operator working: %s%n", matchedCodes);
    }

    @Test
    @DisplayName("Should not match rules with failed numeric conditions")
    void shouldNotMatchFailedNumericConditions() throws Exception {
        // Given - Rules with numeric operators
        Path rulesFile = tempDir.resolve("negative_test.json");
        Files.writeString(rulesFile, """
        [
          {
            "rule_code": "SMALL_AMOUNT",
            "priority": 10,
            "conditions": [
              {"field": "AMOUNT", "operator": "LESS_THAN", "value": 1000}
            ]
          },
          {
            "rule_code": "HUGE_AMOUNT",
            "priority": 200,
            "conditions": [
              {"field": "AMOUNT", "operator": "GREATER_THAN", "value": 100000}
            ]
          }
        ]
        """);

        RuleCompiler compiler = new RuleCompiler(NOOP_TRACER);
        EngineModel model = compiler.compile(rulesFile);
        RuleEvaluator evaluator = new RuleEvaluator(model, NOOP_TRACER, true);

        // When - Evaluate event with amount = 7500
        Event event = new Event("evt-negative", "TEST", Map.of("amount", 7500));
        MatchResult result = evaluator.evaluate(event);

        // Then - Should match NO rules (7500 is not < 1000 and not > 100000)
        assertThat(result.matchedRules())
                .as("Should not match rules where conditions are false")
                .isEmpty();

        System.out.println("✅ PASS: Correctly excluded non-matching numeric rules");
    }
}