package os.toolset.ruleengine.core;

import org.junit.jupiter.api.*;
import os.toolset.ruleengine.model.Event;
import os.toolset.ruleengine.model.MatchResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Comprehensive test suite for the Rule Evaluator, updated for Dictionary Encoding.
 */
class RuleEvaluatorTest {

    private EngineModel model;
    private RuleEvaluator evaluator;
    private static Path tempDir;

    @BeforeAll
    static void beforeAll() throws IOException {
        tempDir = Files.createTempDirectory("rule_engine_test_eval");
    }

    @AfterAll
    static void afterAll() throws IOException {
        Files.walk(tempDir)
                .sorted(java.util.Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(java.io.File::delete);
    }

    @BeforeEach
    void setUp() throws Exception {
        Path rulesFile = tempDir.resolve("rules.json");
        Files.writeString(rulesFile, getTestRulesJson());
        model = new RuleCompiler().compile(rulesFile);
        evaluator = new RuleEvaluator(model);
    }

    @Test
    @DisplayName("Should select highest priority rule in a family")
    void testPerFamilyMaxPrioritySelection() {
        Event event = new Event("evt_family_a", "TEST", Map.of("region", "US", "tier", "PLATINUM"));
        MatchResult result = evaluator.evaluate(event);

        assertThat(result.matchedRules()).hasSize(1);
        assertThat(result.matchedRules().get(0).ruleCode()).isEqualTo("FAMILY_A");
        assertThat(result.matchedRules().get(0).priority()).isEqualTo(100);
    }

    @Test
    @DisplayName("Should evaluate GREATER_THAN operator correctly")
    void testGreaterThanOperator() {
        Event event = new Event("evt_gt", "TEST", Map.of("amount", 6000));
        MatchResult result = evaluator.evaluate(event);
        assertThat(result.matchedRules()).hasSize(1);
        assertThat(result.matchedRules().get(0).ruleCode()).isEqualTo("LARGE_TRANSACTION");
    }

    @Test
    @DisplayName("Should evaluate LESS_THAN operator correctly")
    void testLessThanOperator() {
        Event event = new Event("evt_lt", "TEST", Map.of("amount", 20));
        MatchResult result = evaluator.evaluate(event);
        assertThat(result.matchedRules()).hasSize(1);
        assertThat(result.matchedRules().get(0).ruleCode()).isEqualTo("SMALL_TRANSACTION");
    }

    @Test
    @DisplayName("Should evaluate BETWEEN operator correctly")
    void testBetweenOperator() {
        Event eventInside = new Event("evt_between_in", "TEST", Map.of("amount", 300));
        MatchResult resultInside = evaluator.evaluate(eventInside);
        assertThat(resultInside.matchedRules()).hasSize(1);
        assertThat(resultInside.matchedRules().get(0).ruleCode()).isEqualTo("MID_RANGE_ORDER");

        Event eventOutside = new Event("evt_between_out", "TEST", Map.of("amount", 99));
        MatchResult resultOutside = evaluator.evaluate(eventOutside);
        assertThat(resultOutside.matchedRules()).isEmpty();
    }

    @Test
    @DisplayName("Should evaluate CONTAINS operator correctly")
    void testContainsOperator() {
        Event event = new Event("evt_contains", "TEST", Map.of("product_code", "XYZ-PROMO-123"));
        MatchResult result = evaluator.evaluate(event);
        assertThat(result.matchedRules()).hasSize(1);
        assertThat(result.matchedRules().get(0).ruleCode()).isEqualTo("CONTAINS_CHECK");
    }

    @Test
    @DisplayName("Should evaluate REGEX operator correctly")
    void testRegexOperator() {
        Event eventMatch = new Event("evt_regex_match", "TEST", Map.of("description", "A fast cash withdrawal"));
        MatchResult resultMatch = evaluator.evaluate(eventMatch);
        assertThat(resultMatch.matchedRules()).hasSize(1);
        assertThat(resultMatch.matchedRules().get(0).ruleCode()).isEqualTo("FRAUD_PATTERN");

        Event eventNoMatch = new Event("evt_regex_nomatch", "TEST", Map.of("description", "A standard payment"));
        MatchResult resultNoMatch = evaluator.evaluate(eventNoMatch);
        assertThat(resultNoMatch.matchedRules()).isEmpty();
    }

    @Test
    @DisplayName("Should correctly handle IS_ANY_OF expansion and selection")
    void testIsAnyOfSelection() {
        Event event = new Event("evt_isanyof", "TEST", Map.of("country", "FR"));
        MatchResult result = evaluator.evaluate(event);

        assertThat(result.matchedRules()).hasSize(1);
        assertThat(result.matchedRules().get(0).ruleCode()).isEqualTo("EU_VAT_CHECK");
    }

    private String getTestRulesJson() {
        return """
        [
          {
            "rule_code": "FAMILY_A",
            "priority": 100,
            "description": "High priority rule for top-tier US customers",
            "conditions": [
              {"field": "region", "operator": "EQUAL_TO", "value": "US"},
              {"field": "tier", "operator": "EQUAL_TO", "value": "PLATINUM"}
            ]
          },
          {
            "rule_code": "FAMILY_A",
            "priority": 10,
            "description": "Generic rule for all US customers, lower priority",
            "conditions": [
              {"field": "region", "operator": "EQUAL_TO", "value": "US"}
            ]
          },
          {
            "rule_code": "LARGE_TRANSACTION",
            "priority": 80,
            "conditions": [
              {"field": "amount", "operator": "GREATER_THAN", "value": 5000}
            ]
          },
          {
            "rule_code": "SMALL_TRANSACTION",
            "priority": 70,
            "conditions": [
              {"field": "amount", "operator": "LESS_THAN", "value": 50}
            ]
          },
          {
            "rule_code": "MID_RANGE_ORDER",
            "priority": 60,
            "conditions": [
              {"field": "amount", "operator": "BETWEEN", "value": [100, 500]}
            ]
          },
          {
            "rule_code": "EU_VAT_CHECK",
            "priority": 90,
            "conditions": [
              {"field": "country", "operator": "IS_ANY_OF", "value": ["DE", "FR", "ES"]}
            ]
          },
          {
            "rule_code": "FRAUD_PATTERN",
            "priority": 200,
            "description": "Regex check for suspicious transaction descriptions",
            "conditions": [
              {"field": "description", "operator": "REGEX", "value": ".*(cash|transfer).*"}
            ]
          },
          {
            "rule_code": "CONTAINS_CHECK",
            "priority": 40,
            "description": "Simple contains check for product codes",
            "conditions": [
              {"field": "product_code", "operator": "CONTAINS", "value": "PROMO"}
            ]
          }
        ]
        """;
    }
}