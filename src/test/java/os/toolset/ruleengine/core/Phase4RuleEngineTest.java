package os.toolset.ruleengine.core;

import org.junit.jupiter.api.*;
import os.toolset.ruleengine.model.Event;
import os.toolset.ruleengine.model.MatchResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class Phase4RuleEngineTest {

    private EngineModel model;
    private RuleEvaluator evaluator;
    private static Path tempDir;

    @BeforeAll
    static void beforeAll() throws IOException {
        tempDir = Files.createTempDirectory("rule_engine_phase4_test");
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
        Path rulesFile = tempDir.resolve("phase4_rules.json");
        Files.writeString(rulesFile, getPhase4TestRulesJson());
        model = new RuleCompiler().compile(rulesFile);
        evaluator = new RuleEvaluator(model);
    }

    @Test
    @DisplayName("Should use Structure of Arrays memory layout")
    void testSoAMemoryLayout() {
        int numCombinations = model.getNumRules();
        assertThat(numCombinations).isGreaterThan(0);
        for (int i = 0; i < numCombinations; i++) {
            assertThat(model.getCombinationRuleCode(i)).isNotNull();
            assertThat(model.getCombinationPriority(i)).isGreaterThanOrEqualTo(0);
            assertThat(model.getCombinationPredicateCount(i)).isGreaterThan(0);
            // FIX: Cast to List<?> to resolve ambiguous assertThat call
            assertThat((List<?>) model.getCombinationPredicateIds(i)).isNotNull().isNotEmpty();
        }
    }

    @Test
    @DisplayName("Should maintain correctness with optimizations")
    void testCorrectnessWithOptimizations() {
        Event event1 = new Event("evt1", "TEST", Map.of("country", "US", "status", "ACTIVE"));
        MatchResult result1 = evaluator.evaluate(event1);
        assertThat(result1.matchedRules()).isNotEmpty();

        Event event2 = new Event("evt2", "TEST", Map.of("amount", 15000, "country", "US", "tier", "PLATINUM"));
        MatchResult result2 = evaluator.evaluate(event2);
        assertThat(result2.matchedRules().stream().anyMatch(r -> r.ruleCode().equals("VIP_HIGH_VALUE"))).isTrue();

        Event event3 = new Event("evt3", "TEST", Map.of("unknown_field", "value"));
        MatchResult result3 = evaluator.evaluate(event3);
        assertThat(result3.matchedRules()).isEmpty();
    }

    private String getPhase4TestRulesJson() {
        return """
        [
          {"rule_code": "BASIC_US_RULE", "priority": 50, "conditions": [{"field": "country", "operator": "EQUAL_TO", "value": "US"}, {"field": "status", "operator": "EQUAL_TO", "value": "ACTIVE"}]},
          {"rule_code": "VIP_HIGH_VALUE", "priority": 100, "conditions": [{"field": "amount", "operator": "GREATER_THAN", "value": 10000}, {"field": "tier", "operator": "EQUAL_TO", "value": "PLATINUM"}]}
        ]
        """;
    }
}