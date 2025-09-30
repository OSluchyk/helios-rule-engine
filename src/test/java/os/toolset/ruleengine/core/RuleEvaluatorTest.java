package os.toolset.ruleengine.core;

import org.junit.jupiter.api.*;
import os.toolset.ruleengine.model.Event;
import os.toolset.ruleengine.model.MatchResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
        model = new RuleCompiler(TracingService.getInstance().getTracer()).compile(rulesFile);
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
            "rule_code": "FAMILY_A", "priority": 100,
            "conditions": [{"field": "region", "operator": "EQUAL_TO", "value": "US"}, {"field": "tier", "operator": "EQUAL_TO", "value": "PLATINUM"}]
          },
          {
            "rule_code": "FAMILY_A", "priority": 10,
            "conditions": [{"field": "region", "operator": "EQUAL_TO", "value": "US"}]
          },
          {
            "rule_code": "EU_VAT_CHECK", "priority": 90,
            "conditions": [{"field": "country", "operator": "IS_ANY_OF", "value": ["DE", "FR", "ES"]}]
          }
        ]
        """;
    }
}