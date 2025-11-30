package com.helios.ruleengine.compiler;

import com.helios.ruleengine.api.exceptions.CompilationException;
import com.helios.ruleengine.runtime.model.EngineModel;
import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuleCompilerValidationTest {

    private RuleCompiler compiler;
    private Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        compiler = new RuleCompiler(OpenTelemetry.noop().getTracer("test"));
        tempDir = Files.createTempDirectory("rule_compiler_validation_test");
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
    @DisplayName("Should throw exception for empty rule definitions")
    void shouldThrowForEmptyRules() throws IOException {
        Path rulesFile = writeRules("[]");
        assertThatThrownBy(() -> compiler.compile(rulesFile))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("Rule definitions cannot be empty");
    }

    @Test
    @DisplayName("Should throw exception for missing rule code")
    void shouldThrowForMissingRuleCode() throws IOException {
        String json = """
                [
                    {
                        "conditions": [{"field": "status", "operator": "EQUAL_TO", "value": "A"}]
                    }
                ]
                """;
        Path rulesFile = writeRules(json);
        assertThatThrownBy(() -> compiler.compile(rulesFile))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("missing or empty rule_code");
    }

    @Test
    @DisplayName("Should throw exception for duplicate rule code")
    void shouldThrowForDuplicateRuleCode() throws IOException {
        String json = """
                [
                    {"rule_code": "R1", "conditions": [{"field": "a", "operator": "EQUAL_TO", "value": 1}]},
                    {"rule_code": "R1", "conditions": [{"field": "b", "operator": "EQUAL_TO", "value": 2}]}
                ]
                """;
        Path rulesFile = writeRules(json);
        assertThatThrownBy(() -> compiler.compile(rulesFile))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("Duplicate rule_code: R1");
    }

    @Test
    @DisplayName("Should throw exception for unknown operator")
    void shouldThrowForUnknownOperator() throws IOException {
        String json = """
                [
                    {
                        "rule_code": "R1",
                        "conditions": [{"field": "status", "operator": "UNKNOWN_OP", "value": "A"}]
                    }
                ]
                """;
        Path rulesFile = writeRules(json);
        assertThatThrownBy(() -> compiler.compile(rulesFile))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("unknown operator: UNKNOWN_OP");
    }

    @Test
    @DisplayName("Should throw exception for missing field")
    void shouldThrowForMissingField() throws IOException {
        String json = """
                [
                    {
                        "rule_code": "R1",
                        "conditions": [{"operator": "EQUAL_TO", "value": "A"}]
                    }
                ]
                """;
        Path rulesFile = writeRules(json);
        assertThatThrownBy(() -> compiler.compile(rulesFile))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("has null field");
    }

    @Test
    @DisplayName("Should throw exception for invalid IS_ANY_OF value")
    void shouldThrowForInvalidIsAnyOfValue() throws IOException {
        String json = """
                [
                    {
                        "rule_code": "R1",
                        "conditions": [{"field": "status", "operator": "IS_ANY_OF", "value": "NOT_A_LIST"}]
                    }
                ]
                """;
        Path rulesFile = writeRules(json);
        assertThatThrownBy(() -> compiler.compile(rulesFile))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("requires array value");
    }

    @Test
    @DisplayName("Should throw exception for invalid numeric value")
    void shouldThrowForInvalidNumericValue() throws IOException {
        String json = """
                [
                    {
                        "rule_code": "R1",
                        "conditions": [{"field": "amount", "operator": "GREATER_THAN", "value": "NOT_A_NUMBER"}]
                    }
                ]
                """;
        Path rulesFile = writeRules(json);
        assertThatThrownBy(() -> compiler.compile(rulesFile))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("requires numeric value");
    }

    @Test
    @DisplayName("Should throw exception for invalid regex")
    void shouldThrowForInvalidRegex() throws IOException {
        String json = """
                [
                    {
                        "rule_code": "R1",
                        "conditions": [{"field": "name", "operator": "REGEX", "value": "["}]
                    }
                ]
                """;
        Path rulesFile = writeRules(json);
        assertThatThrownBy(() -> compiler.compile(rulesFile))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("invalid regex pattern");
    }

    @Test
    @DisplayName("Should skip rule with contradictory conditions")
    void shouldSkipContradictoryRule() throws IOException {
        // x > 100 AND x < 50 is impossible
        String json = """
                [
                    {
                        "rule_code": "R1",
                        "conditions": [
                            {"field": "amount", "operator": "GREATER_THAN", "value": 100},
                            {"field": "amount", "operator": "LESS_THAN", "value": 50}
                        ]
                    }
                ]
                """;
        Path rulesFile = writeRules(json);
        EngineModel model = compiler.compile(rulesFile);

        // The rule should be compiled but result in NO combinations because it's
        // impossible
        assertThat(model.getNumRules()).isEqualTo(0);
    }

    @Test
    @DisplayName("Should compile valid rule with multiple conditions")
    void shouldCompileValidRule() throws IOException, CompilationException {
        String json = """
                [
                    {
                        "rule_code": "R1",
                        "conditions": [
                            {"field": "status", "operator": "EQUAL_TO", "value": "ACTIVE"},
                            {"field": "amount", "operator": "GREATER_THAN", "value": 100},
                            {"field": "country", "operator": "IS_ANY_OF", "value": ["US", "CA"]}
                        ]
                    }
                ]
                """;
        Path rulesFile = writeRules(json);
        EngineModel model = compiler.compile(rulesFile);

        assertThat(model.getNumRules()).isEqualTo(2); // 2 combinations (US, CA)
    }
}
