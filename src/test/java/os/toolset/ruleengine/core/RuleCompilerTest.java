package os.toolset.ruleengine.core;

import org.junit.jupiter.api.*;
import java.io.IOException;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class RuleCompilerTest {

    private RuleCompiler compiler;
    private Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        compiler = new RuleCompiler();
        tempDir = Files.createTempDirectory("rule_engine_test");
    }

    @AfterEach
    void tearDown() throws IOException {
        Files.walk(tempDir)
                .sorted(java.util.Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(java.io.File::delete);
    }

    @Test
    @DisplayName("Should compile valid rules successfully")
    void testSuccessfulCompilation() throws Exception {
        String rulesJson = """
            [
                {
                    "rule_code": "RULE_001",
                    "conditions": [
                        {"field": "status", "operator": "EQUAL_TO", "value": "ACTIVE"},
                        {"field": "amount", "operator": "EQUAL_TO", "value": 1000}
                    ],
                    "priority": 10,
                    "description": "Test rule",
                    "enabled": true
                }
            ]
            """;

        Path rulesFile = tempDir.resolve("rules.json");
        Files.writeString(rulesFile, rulesJson);

        EngineModel model = compiler.compile(rulesFile);

        assertThat(model.getRuleStore()).hasSize(1);
        assertThat(model.getPredicateRegistry()).hasSize(2);
    }

    @Test
    @DisplayName("Should reject rules with unsupported operators")
    void testUnsupportedOperator() throws IOException {
        String rulesJson = """
            [
                {
                    "rule_code": "RULE_001",
                    "conditions": [
                        {"field": "amount", "operator": "GREATER_THAN", "value": 1000}
                    ]
                }
            ]
            """;

        Path rulesFile = tempDir.resolve("rules.json");
        Files.writeString(rulesFile, rulesJson);

        assertThatThrownBy(() -> compiler.compile(rulesFile))
                .isInstanceOf(RuleCompiler.CompilationException.class)
                .hasMessageContaining("MVP only supports EQUAL_TO");
    }

    @Test
    @DisplayName("Should reject duplicate rule codes")
    void testDuplicateRuleCodes() throws IOException {
        String rulesJson = """
            [
                {"rule_code": "RULE_001", "conditions": [{"field": "f", "operator": "EQUAL_TO", "value": "v"}]},
                {"rule_code": "RULE_001", "conditions": [{"field": "f", "operator": "EQUAL_TO", "value": "v"}]}
            ]
            """;

        Path rulesFile = tempDir.resolve("rules.json");
        Files.writeString(rulesFile, rulesJson);

        assertThatThrownBy(() -> compiler.compile(rulesFile))
                .isInstanceOf(RuleCompiler.CompilationException.class)
                .hasMessageContaining("Duplicate rule_code");
    }

    @Test
    @DisplayName("Should skip disabled rules")
    void testDisabledRules() throws Exception {
        String rulesJson = """
            [
                {
                    "rule_code": "ENABLED_RULE",
                    "conditions": [{"field": "status", "operator": "EQUAL_TO", "value": "ACTIVE"}],
                    "enabled": true
                },
                {
                    "rule_code": "DISABLED_RULE",
                    "conditions": [{"field": "status", "operator": "EQUAL_TO", "value": "INACTIVE"}],
                    "enabled": false
                }
            ]
            """;

        Path rulesFile = tempDir.resolve("rules.json");
        Files.writeString(rulesFile, rulesJson);

        EngineModel model = compiler.compile(rulesFile);

        assertThat(model.getRuleStore()).hasSize(1);
        assertThat(model.getRuleByCode("ENABLED_RULE")).isNotNull();
        assertThat(model.getRuleByCode("DISABLED_RULE")).isNull();
    }

    @Test
    @DisplayName("Should handle empty conditions correctly")
    void testEmptyConditions() throws IOException {
        String rulesJson = """
            [
                {
                    "rule_code": "EMPTY_RULE",
                    "conditions": []
                }
            ]
            """;

        Path rulesFile = tempDir.resolve("rules.json");
        Files.writeString(rulesFile, rulesJson);

        assertThatThrownBy(() -> compiler.compile(rulesFile))
                .isInstanceOf(RuleCompiler.CompilationException.class)
                .hasMessageContaining("must have at least one condition");
    }

    @Test
    @DisplayName("Should deduplicate predicates")
    void testPredicateDeduplication() throws Exception {
        String rulesJson = """
            [
                {
                    "rule_code": "RULE_001",
                    "conditions": [
                        {"field": "status", "operator": "EQUAL_TO", "value": "ACTIVE"},
                        {"field": "amount", "operator": "EQUAL_TO", "value": 1000}
                    ]
                },
                {
                    "rule_code": "RULE_002",
                    "conditions": [
                        {"field": "status", "operator": "EQUAL_TO", "value": "ACTIVE"},
                        {"field": "region", "operator": "EQUAL_TO", "value": "US"}
                    ]
                }
            ]
            """;

        Path rulesFile = tempDir.resolve("rules.json");
        Files.writeString(rulesFile, rulesJson);

        EngineModel model = compiler.compile(rulesFile);

        // Should have 3 unique predicates, not 4
        assertThat(model.getPredicateRegistry()).hasSize(3);
        assertThat(model.getRuleStore()).hasSize(2);
    }
}