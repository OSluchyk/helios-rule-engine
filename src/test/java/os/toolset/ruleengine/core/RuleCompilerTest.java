package os.toolset.ruleengine.core;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import os.toolset.ruleengine.model.Predicate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class RuleCompilerTest {

    private RuleCompiler compiler;
    private Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        compiler = new RuleCompiler(TracingService.getInstance().getTracer());
        tempDir = Files.createTempDirectory("rule_engine_test");
    }

    @AfterEach
    void tearDown() throws IOException {
        Files.walk(tempDir)
                .sorted(java.util.Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(java.io.File::delete);
    }

    private Path writeRules(String json) throws IOException {
        Path rulesFile = tempDir.resolve("rules.json");
        Files.writeString(rulesFile, json);
        return rulesFile;
    }

    @Test
    @DisplayName("Should expand and deduplicate rules correctly")
    void testDeduplication() throws Exception {
        String rulesJson = """
        [
            {
                "rule_code": "RULE_A",
                "conditions": [
                    {"field": "status", "operator": "EQUAL_TO", "value": "ACTIVE"},
                    {"field": "country", "operator": "IS_ANY_OF", "value": ["US", "CA"]}
                ]
            },
            {
                "rule_code": "RULE_B",
                "conditions": [
                    {"field": "status", "operator": "EQUAL_TO", "value": "ACTIVE"},
                    {"field": "country", "operator": "IS_ANY_OF", "value": ["US", "UK"]}
                ]
            }
        ]
        """;
        Path rulesFile = writeRules(rulesJson);
        EngineModel model = compiler.compile(rulesFile);

        // Corrected Assertions
        assertThat(model.getStats().metadata().get("totalExpandedCombinations")).isEqualTo(4);
        assertThat(model.getStats().metadata().get("uniqueCombinations")).isEqualTo(3);
        assertThat(model.getNumRules()).isEqualTo(3);


        int statusFieldId = model.getFieldDictionary().getId("STATUS");
        int countryFieldId = model.getFieldDictionary().getId("COUNTRY");
        int activeValueId = model.getValueDictionary().getId("ACTIVE");
        int usValueId = model.getValueDictionary().getId("US");

        // Corrected Predicate constructor call
        Predicate p1 = new Predicate(statusFieldId, Predicate.Operator.EQUAL_TO, activeValueId, null, 0.5f, 0.5f);
        Predicate p2 = new Predicate(countryFieldId, Predicate.Operator.EQUAL_TO, usValueId, null, 0.5f, 0.5f);

        int p1Id = model.getPredicateId(p1);
        int p2Id = model.getPredicateId(p2);

        IntList sharedCombination = new IntArrayList(new int[]{p1Id, p2Id});
        sharedCombination.sort(null);

        boolean foundSharedCombination = false;
        for (int i = 0; i < model.getNumRules(); i++) {
            IntList combinationPreds = new IntArrayList(model.getCombinationPredicateIds(i));
            combinationPreds.sort(null);
            if (combinationPreds.equals(sharedCombination)) {
                foundSharedCombination = true;
                break;
            }
        }
        assertThat(foundSharedCombination).isTrue();
    }
}