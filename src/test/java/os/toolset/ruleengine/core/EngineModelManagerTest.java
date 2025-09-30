package os.toolset.ruleengine.core;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import os.toolset.ruleengine.model.Event;
import os.toolset.ruleengine.model.MatchResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

public class EngineModelManagerTest {

    private static Path tempDir;

    @BeforeAll
    static void beforeAll() throws IOException {
        tempDir = Files.createTempDirectory("hot_reload_test");
    }

    @AfterAll
    static void afterAll() throws IOException {
        Files.walk(tempDir)
                .sorted(java.util.Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(java.io.File::delete);
    }

    @Test
    void shouldHotReloadRulesOnFileChange() throws Exception {
        Path rulesFile = tempDir.resolve("rules.json");

        // --- Initial State ---
        String initialRules = """
        [
          {"rule_code": "ALPHA", "conditions": [{"field": "type", "operator": "EQUAL_TO", "value": "A"}]}
        ]
        """;
        Files.writeString(rulesFile, initialRules);

        EngineModelManager manager = new EngineModelManager(rulesFile);
        manager.start();

        RuleEvaluator evaluator1 = new RuleEvaluator(manager.getEngineModel());
        MatchResult result1 = evaluator1.evaluate(new Event("1", "T", Map.of("type", "A")));
        assertThat(result1.matchedRules()).hasSize(1);
        assertThat(result1.matchedRules().get(0).ruleCode()).isEqualTo("ALPHA");

        // --- Update the rule file ---
        // This is a manual wait. In a real app, this would be asynchronous.
        // We wait long enough for the file modification time to be different.
        TimeUnit.SECONDS.sleep(2);

        String updatedRules = """
        [
          {"rule_code": "BETA", "conditions": [{"field": "type", "operator": "EQUAL_TO", "value": "A"}]}
        ]
        """;
        Files.writeString(rulesFile, updatedRules);

        // --- Verify Reload ---
        // Wait for the monitor to detect the change. 12 seconds should be enough (default is 10s interval).
        System.out.println("Waiting for rule reload...");
        TimeUnit.SECONDS.sleep(12);

        RuleEvaluator evaluator2 = new RuleEvaluator(manager.getEngineModel());
        MatchResult result2 = evaluator2.evaluate(new Event("2", "T", Map.of("type", "A")));
        assertThat(result2.matchedRules()).hasSize(1);
        // The rule code should now be BETA, proving the new model was loaded.
        assertThat(result2.matchedRules().get(0).ruleCode()).isEqualTo("BETA");

        manager.shutdown();
    }
}