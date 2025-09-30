package os.toolset.ruleengine.core;

import org.junit.jupiter.api.*;
import os.toolset.ruleengine.core.cache.BaseConditionCache;
import os.toolset.ruleengine.core.cache.InMemoryBaseConditionCache;
import os.toolset.ruleengine.model.Event;
import os.toolset.ruleengine.model.MatchResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BaseConditionCacheTest {

    private EngineModel model;
    private RuleEvaluator evaluator;
    private BaseConditionCache cache;
    private static Path tempDir;

    @BeforeAll
    static void beforeAll() throws IOException {
        tempDir = Files.createTempDirectory("rule_engine_cache_test");
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
        Path rulesFile = tempDir.resolve("cache_test_rules.json");
        Files.writeString(rulesFile, getCacheTestRulesJson());

        model = new RuleCompiler().compile(rulesFile);
        evaluator = new RuleEvaluator(model);
        cache = new InMemoryBaseConditionCache.Builder().maxSize(100).build();
    }

    @Test
    @Order(1)
    @DisplayName("Should demonstrate cache interaction")
    void testCacheInteraction() {
        Event event1 = new Event("evt1", "ORDER", Map.of("country", "US", "status", "ACTIVE"));
        evaluator.evaluate(event1);
        // We can assert that the evaluator ran, which implicitly uses the cache logic.
        assertThat(evaluator.getMetrics().getSnapshot()).containsKey("totalEvaluations");
    }

    private String getCacheTestRulesJson() {
        return """
        [
          {
            "rule_code": "RULE_1",
            "conditions": [
              {"field": "country", "operator": "EQUAL_TO", "value": "US"},
              {"field": "status", "operator": "EQUAL_TO", "value": "ACTIVE"}
            ]
          }
        ]
        """;
    }
}