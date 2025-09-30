package os.toolset.ruleengine.core;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import os.toolset.ruleengine.core.cache.InMemoryBaseConditionCache;
import os.toolset.ruleengine.model.Event;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;

public class BaseConditionEvaluatorTest {

    private EngineModel model;
    private InMemoryBaseConditionCache cache;
    private BaseConditionEvaluator baseEvaluator;
    private static Path tempDir;

    @BeforeAll
    static void beforeAll() throws IOException {
        tempDir = Files.createTempDirectory("base_eval_test");
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
        Path rulesFile = tempDir.resolve("base_eval_rules.json");
        Files.writeString(rulesFile, getTestRulesJson());
        model = new RuleCompiler().compile(rulesFile);
        cache = new InMemoryBaseConditionCache.Builder().maxSize(100).build();
        baseEvaluator = new BaseConditionEvaluator(model, cache);
    }

    @Test
    void shouldExtractBaseConditionSets() {
        // The test rules have 2 unique static conditions: {status=ACTIVE} and {country=US, type=RENEWAL}
        // So we expect 2 base condition sets to be extracted.
        assertThat(baseEvaluator.getMetrics().get("baseConditionSets")).isEqualTo(2);
    }

    @Test
    void shouldReturnCacheHitForIdenticalEvents() throws ExecutionException, InterruptedException {
        Event event = new Event("evt1", "TEST", Map.of("status", "ACTIVE", "amount", 100));

        // First call - should be a cache miss
        BaseConditionEvaluator.EvaluationResult result1 = baseEvaluator.evaluateBaseConditions(event).get();
        assertThat(result1.fromCache).isFalse();
        assertThat(result1.predicatesEvaluated).isGreaterThan(0);
        assertThat(cache.getMetrics().misses()).isEqualTo(1);

        // Second call - should be a cache hit
        BaseConditionEvaluator.EvaluationResult result2 = baseEvaluator.evaluateBaseConditions(event).get();
        assertThat(result2.fromCache).isTrue();
        assertThat(result2.predicatesEvaluated).isEqualTo(0); // No predicates evaluated on cache hit
        assertThat(cache.getMetrics().hits()).isEqualTo(1);
    }

    @Test
    void shouldCorrectlyFilterCombinations() throws ExecutionException, InterruptedException {
        // This event matches the {status=ACTIVE} base condition, but not the {country=US, type=RENEWAL} one.
        Event event = new Event("evt1", "TEST", Map.of("status", "ACTIVE", "country", "CA"));

        BaseConditionEvaluator.EvaluationResult result = baseEvaluator.evaluateBaseConditions(event).get();

        // Model has 3 combinations total.
        // Combo 0: {status=ACTIVE, amount>1000} -> Base condition matches
        // Combo 1: {country=US, type=RENEWAL, amount<50} -> Base condition fails
        // Combo 2: {status=ACTIVE, amount<100} -> Base condition matches
        // We expect 2 of the 3 combinations to remain eligible.
        assertThat(result.matchingRules.cardinality()).isEqualTo(2);
    }


    private String getTestRulesJson() {
        return """
        [
          {
            "rule_code": "HIGH_VALUE_ACTIVE",
            "conditions": [
              {"field": "status", "operator": "EQUAL_TO", "value": "ACTIVE"},
              {"field": "amount", "operator": "GREATER_THAN", "value": 1000}
            ]
          },
          {
            "rule_code": "US_RENEWAL",
            "conditions": [
              {"field": "country", "operator": "EQUAL_TO", "value": "US"},
              {"field": "type", "operator": "EQUAL_TO", "value": "RENEWAL"},
              {"field": "amount", "operator": "LESS_THAN", "value": 50}
            ]
          },
          {
            "rule_code": "LOW_VALUE_ACTIVE",
            "conditions": [
              {"field": "status", "operator": "EQUAL_TO", "value": "ACTIVE"},
              {"field": "amount", "operator": "LESS_THAN", "value": 100}
            ]
          }
        ]
        """;
    }
}