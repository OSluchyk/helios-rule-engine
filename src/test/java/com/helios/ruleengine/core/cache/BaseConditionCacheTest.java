package com.helios.ruleengine.core.cache;

import com.helios.ruleengine.core.compiler.DefaultRuleCompiler;
import com.helios.ruleengine.core.evaluation.DefaultRuleEvaluator;
import com.helios.ruleengine.core.model.EngineModel;
import com.helios.ruleengine.infrastructure.telemetry.TracingService;
import org.junit.jupiter.api.*;
import com.helios.ruleengine.model.Event;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class BaseConditionCacheTest {
    private DefaultRuleEvaluator evaluator;
    private static Path tempDir;

    @BeforeAll
    static void beforeAll() throws IOException {
        tempDir = Files.createTempDirectory("rule_engine_cache_test");
    }

    @AfterAll
    static void afterAll() throws IOException {
        Files.walk(tempDir).sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
            try { Files.delete(path); } catch (IOException e) {}
        });
    }

    @BeforeEach
    void setUp() throws Exception {
        Path rulesFile = tempDir.resolve("cache_test_rules.json");
        Files.writeString(rulesFile, "[{\"rule_code\": \"RULE_1\", \"conditions\": [{\"field\": \"country\", \"operator\": \"EQUAL_TO\", \"value\": \"US\"}]}]");
        EngineModel model = new DefaultRuleCompiler(TracingService.getInstance().getTracer()).compile(rulesFile);
        evaluator = new DefaultRuleEvaluator(model);
    }

    @Test
    void testCacheInteraction() {
        evaluator.evaluate(new Event("evt1", "ORDER", Map.of("country", "US")));
        assertThat(evaluator.getMetrics().getSnapshot()).containsKey("totalEvaluations");
    }
}