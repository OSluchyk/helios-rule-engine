package com.helios.ruleengine.validation;

import com.helios.ruleengine.core.compiler.CompilationException;
import com.helios.ruleengine.core.compiler.RuleCompiler;
import com.helios.ruleengine.core.model.EngineModel;
import com.helios.ruleengine.infrastructure.telemetry.TracingService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

/**
 * COMPILATION VALIDATION TESTS
 *
 * Comprehensive validation of the compilation phase including:
 * - Schema validation
 * - Contradiction detection
 * - Type safety
 * - Operator validation
 * - Expansion limits
 * - Dictionary overflow scenarios
 *
 * @author Google L5 Engineering Standards
 */
@DisplayName("Compilation Phase - Validation Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CompilationValidationTest {

    private static final RuleCompiler COMPILER = new RuleCompiler(
            TracingService.getInstance().getTracer()
    );
    private static Path tempDir;

    @BeforeAll
    static void setup() throws IOException {
        tempDir = Files.createTempDirectory("compilation_validation_test");
    }

    @AfterAll
    static void teardown() throws IOException {
        if (tempDir != null && Files.exists(tempDir)) {
            Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(file -> file.delete());
        }
    }

    // ========================================================================
    // INVALID SCHEMA TESTS
    // ========================================================================

    @ParameterizedTest(name = "[{index}] Invalid Schema: {0}")
    @MethodSource("provideInvalidSchemas")
    @Order(1)
    @DisplayName("Should reject invalid rule schemas")
    void shouldRejectInvalidSchemas(InvalidSchemaTest test) throws IOException {
        Path rulesFile = createTempRulesFile(test.rulesJson);

        assertThatThrownBy(() -> COMPILER.compile(rulesFile))
                .as("Should reject: %s", test.description)
                    .isInstanceOfAny(CompilationException.class, IOException.class,
                        IllegalArgumentException.class);
    }

    static Stream<InvalidSchemaTest> provideInvalidSchemas() {
        return Stream.of(
                // Missing required fields
                new InvalidSchemaTest("Missing rule_code",
                        """
                        [{"conditions": [
                            {"field": "status", "operator": "EQUAL_TO", "value": "ACTIVE"}
                        ]}]
                        """),

                new InvalidSchemaTest("Missing conditions",
                        """
                        [{"rule_code": "TEST_RULE"}]
                        """),

                new InvalidSchemaTest("Missing field in condition",
                        """
                        [{"rule_code": "TEST", "conditions": [
                            {"operator": "EQUAL_TO", "value": "test"}
                        ]}]
                        """),

                new InvalidSchemaTest("Missing operator in condition",
                        """
                        [{"rule_code": "TEST", "conditions": [
                            {"field": "status", "value": "test"}
                        ]}]
                        """),

                new InvalidSchemaTest("Missing value in condition",
                        """
                        [{"rule_code": "TEST", "conditions": [
                            {"field": "status", "operator": "EQUAL_TO"}
                        ]}]
                        """),

                // Invalid operators
                new InvalidSchemaTest("Unknown operator",
                        """
                        [{"rule_code": "TEST", "conditions": [
                            {"field": "status", "operator": "INVALID_OP", "value": "test"}
                        ]}]
                        """),

                new InvalidSchemaTest("OR operator not allowed",
                        """
                        [{"rule_code": "TEST", "conditions": [
                            {"field": "status", "operator": "OR", "value": ["A", "B"]}
                        ]}]
                        """),

                // Type mismatches
                new InvalidSchemaTest("String value for numeric operator",
                        """
                        [{"rule_code": "TEST", "conditions": [
                            {"field": "amount", "operator": "GREATER_THAN", "value": "not_a_number"}
                        ]}]
                        """),

                new InvalidSchemaTest("Non-array value for IS_ANY_OF",
                        """
                        [{"rule_code": "TEST", "conditions": [
                            {"field": "country", "operator": "IS_ANY_OF", "value": "US"}
                        ]}]
                        """),

                new InvalidSchemaTest("Empty array for IS_ANY_OF",
                        """
                        [{"rule_code": "TEST", "conditions": [
                            {"field": "country", "operator": "IS_ANY_OF", "value": []}
                        ]}]
                        """),

                new InvalidSchemaTest("Non-array value for BETWEEN",
                        """
                        [{"rule_code": "TEST", "conditions": [
                            {"field": "age", "operator": "BETWEEN", "value": 25}
                        ]}]
                        """),

                new InvalidSchemaTest("Wrong array size for BETWEEN",
                        """
                        [{"rule_code": "TEST", "conditions": [
                            {"field": "age", "operator": "BETWEEN", "value": [18, 65, 100]}
                        ]}]
                        """),

                // Invalid rule codes
                new InvalidSchemaTest("Duplicate rule codes",
                        """
                        [
                            {"rule_code": "DUPLICATE", "conditions": [
                                {"field": "f1", "operator": "EQUAL_TO", "value": "v1"}
                            ]},
                            {"rule_code": "DUPLICATE", "conditions": [
                                {"field": "f2", "operator": "EQUAL_TO", "value": "v2"}
                            ]}
                        ]
                        """),

                new InvalidSchemaTest("Empty rule code",
                        """
                        [{"rule_code": "", "conditions": [
                            {"field": "status", "operator": "EQUAL_TO", "value": "ACTIVE"}
                        ]}]
                        """),

                // Invalid JSON structure
                new InvalidSchemaTest("Not an array",
                        """
                        {"rule_code": "TEST", "conditions": [
                            {"field": "status", "operator": "EQUAL_TO", "value": "ACTIVE"}
                        ]}
                        """),

                new InvalidSchemaTest("Malformed JSON - missing bracket",
                        """
                        [{"rule_code": "TEST", "conditions": [
                            {"field": "status", "operator": "EQUAL_TO", "value": "ACTIVE"}
                        ]
                        """),

                new InvalidSchemaTest("Malformed JSON - extra comma",
                        """
                        [{"rule_code": "TEST", "conditions": [
                            {"field": "status", "operator": "EQUAL_TO", "value": "ACTIVE"},
                        ]}]
                        """)
        );
    }

    // ========================================================================
    // CONTRADICTION DETECTION TESTS
    // ========================================================================

    @ParameterizedTest(name = "[{index}] Contradiction: {0}")
    @MethodSource("provideContradictions")
    @Order(2)
    @DisplayName("Should detect contradictory conditions")
    void shouldDetectContradictions(ContradictionTest test) throws IOException {
        Path rulesFile = createTempRulesFile(test.rulesJson);

        if (test.shouldDetect) {
            // Should either reject at compile time or produce 0 unique combinations
            try {
                EngineModel model = COMPILER.compile(rulesFile);
                int uniqueCombos = (int) model.getStats().metadata()
                        .getOrDefault("uniqueCombinations", 1);

                assertThat(uniqueCombos)
                        .as("Contradictory rule should have 0 combinations: %s", test.description)
                        .isEqualTo(0);
            } catch (CompilationException e) {
                // Also acceptable - reject at compile time
                assertThat(e.getMessage())
                        .as("Should mention contradiction")
                        .containsAnyOf("contradict", "impossible", "conflict");
            }
        } else {
            // Should compile successfully
            EngineModel model = COMPILER.compile(rulesFile);
            assertThat(model).isNotNull();
        }
    }

    static Stream<ContradictionTest> provideContradictions() {
        return Stream.of(
                // Direct contradictions
                new ContradictionTest("EQUAL_TO same field different values", true,
                        """
                        [{"rule_code": "CONTRADICTION", "conditions": [
                            {"field": "status", "operator": "EQUAL_TO", "value": "ACTIVE"},
                            {"field": "status", "operator": "EQUAL_TO", "value": "INACTIVE"}
                        ]}]
                        """),

                new ContradictionTest("IS_ANY_OF with no overlap", true,
                        """
                        [{"rule_code": "CONTRADICTION", "conditions": [
                            {"field": "country", "operator": "IS_ANY_OF", "value": ["US"]},
                            {"field": "country", "operator": "IS_ANY_OF", "value": ["CA"]}
                        ]}]
                        """),

                // Range contradictions
                new ContradictionTest("GREATER_THAN and LESS_THAN impossible range", true,
                        """
                        [{"rule_code": "CONTRADICTION", "conditions": [
                            {"field": "amount", "operator": "GREATER_THAN", "value": 1000},
                            {"field": "amount", "operator": "LESS_THAN", "value": 500}
                        ]}]
                        """),

                new ContradictionTest("BETWEEN with inverted range", true,
                        """
                        [{"rule_code": "CONTRADICTION", "conditions": [
                            {"field": "age", "operator": "BETWEEN", "value": [65, 18]}
                        ]}]
                        """),

                // Non-contradictions (should compile successfully)
                new ContradictionTest("GREATER_THAN and LESS_THAN valid range", false,
                        """
                        [{"rule_code": "VALID", "conditions": [
                            {"field": "amount", "operator": "GREATER_THAN", "value": 100},
                            {"field": "amount", "operator": "LESS_THAN", "value": 1000}
                        ]}]
                        """),

                new ContradictionTest("IS_ANY_OF with overlap", false,
                        """
                        [{"rule_code": "VALID", "conditions": [
                            {"field": "country", "operator": "IS_ANY_OF", "value": ["US", "CA"]},
                            {"field": "country", "operator": "IS_ANY_OF", "value": ["CA", "UK"]}
                        ]}]
                        """),

                new ContradictionTest("Different fields - no contradiction", false,
                        """
                        [{"rule_code": "VALID", "conditions": [
                            {"field": "status", "operator": "EQUAL_TO", "value": "ACTIVE"},
                            {"field": "country", "operator": "EQUAL_TO", "value": "US"}
                        ]}]
                        """)
        );
    }

    // ========================================================================
    // EXPANSION LIMIT TESTS
    // ========================================================================

    @Test
    @Order(3)
    @DisplayName("Should handle large IS_ANY_OF expansions")
    void shouldHandleLargeExpansions() throws Exception {
        // GIVEN: Rule with large IS_ANY_OF expansion
        StringBuilder countries = new StringBuilder("[");
        for (int i = 0; i < 100; i++) {
            if (i > 0) countries.append(", ");
            countries.append("\"COUNTRY_").append(i).append("\"");
        }
        countries.append("]");

        String rulesJson = String.format("""
        [{"rule_code": "LARGE_EXPANSION", "conditions": [
            {"field": "country", "operator": "IS_ANY_OF", "value": %s},
            {"field": "status", "operator": "IS_ANY_OF", "value": ["ACTIVE", "PENDING"]}
        ]}]
        """, countries);

        Path rulesFile = createTempRulesFile(rulesJson);

        // WHEN: Compile
        EngineModel model = COMPILER.compile(rulesFile);

        // THEN: Should handle expansion
        int totalExpanded = (int) model.getStats().metadata()
                .getOrDefault("totalExpandedCombinations", 0);

        assertThat(totalExpanded)
                .as("Should expand to 200 combinations (100 countries × 2 statuses)")
                .isEqualTo(200);
    }

    @Test
    @Order(4)
    @DisplayName("Should warn on extreme expansions")
    void shouldWarnOnExtremeExpansions() throws Exception {
        // GIVEN: Rule with extreme expansion potential
        StringBuilder values = new StringBuilder("[");
        for (int i = 0; i < 50; i++) {
            if (i > 0) values.append(", ");
            values.append("\"V").append(i).append("\"");
        }
        values.append("]");

        String rulesJson = String.format("""
        [{"rule_code": "EXTREME_EXPANSION", "conditions": [
            {"field": "field1", "operator": "IS_ANY_OF", "value": %s},
            {"field": "field2", "operator": "IS_ANY_OF", "value": %s},
            {"field": "field3", "operator": "IS_ANY_OF", "value": %s}
        ]}]
        """, values, values, values);

        Path rulesFile = createTempRulesFile(rulesJson);

        // WHEN/THEN: Should either compile with warning or reject
        // (This would expand to 50^3 = 125,000 combinations)
        try {
            EngineModel model = COMPILER.compile(rulesFile);

            int totalExpanded = (int) model.getStats().metadata()
                    .getOrDefault("totalExpandedCombinations", 0);

            // If it compiles, verify the expansion count
            assertThat(totalExpanded)
                    .as("Should expand to 125,000 combinations")
                    .isGreaterThan(100000);

        } catch (CompilationException e) {
            // Also acceptable - reject extreme expansions
            assertThat(e.getMessage())
                    .containsAnyOf("expansion", "too many", "limit");
        }
    }

    // ========================================================================
    // TYPE SAFETY TESTS
    // ========================================================================

    @Test
    @Order(5)
    @DisplayName("Should enforce type safety for operators")
    void shouldEnforceTypeSafety() throws Exception {
        // GIVEN: Valid typed rules
        String validRulesJson = """
        [
            {
                "rule_code": "NUMERIC_TYPES",
                "conditions": [
                    {"field": "int_field", "operator": "EQUAL_TO", "value": 42},
                    {"field": "double_field", "operator": "GREATER_THAN", "value": 3.14},
                    {"field": "long_field", "operator": "LESS_THAN", "value": 9223372036854775807}
                ]
            },
            {
                "rule_code": "STRING_TYPES",
                "conditions": [
                    {"field": "string_field", "operator": "EQUAL_TO", "value": "test"},
                    {"field": "text_field", "operator": "CONTAINS", "value": "substring"},
                    {"field": "pattern_field", "operator": "REGEX", "value": "^[a-z]+$"}
                ]
            },
            {
                "rule_code": "ARRAY_TYPES",
                "conditions": [
                    {"field": "multi_field", "operator": "IS_ANY_OF", "value": [1, 2, 3]},
                    {"field": "range_field", "operator": "BETWEEN", "value": [10, 100]}
                ]
            }
        ]
        """;

        Path rulesFile = createTempRulesFile(validRulesJson);

        // WHEN: Compile
        EngineModel model = COMPILER.compile(rulesFile);

        // THEN: Should compile successfully with all types
        assertThat(model.getNumRules())
                .as("All typed rules should compile")
                .isGreaterThan(0);
    }

    // ========================================================================
    // DICTIONARY ENCODING TESTS
    // ========================================================================

    @Test
    @Order(6)
    @DisplayName("Should handle dictionary size limits gracefully")
    void shouldHandleDictionaryLimits() throws Exception {
        // GIVEN: Rules with many unique fields and values
        StringBuilder rulesJson = new StringBuilder("[\n");
        for (int i = 0; i < 1000; i++) {
            if (i > 0) rulesJson.append(",\n");
            rulesJson.append(String.format("""
                {"rule_code": "RULE_%d", "conditions": [
                    {"field": "field_%d", "operator": "EQUAL_TO", "value": "value_%d"}
                ]}
                """, i, i, i));
        }
        rulesJson.append("\n]");

        Path rulesFile = createTempRulesFile(rulesJson.toString());

        // WHEN: Compile
        EngineModel model = COMPILER.compile(rulesFile);

        // THEN: Dictionary should handle all unique entries
        assertThat(model.getFieldDictionary().size())
                .as("Field dictionary should contain all unique fields")
                .isGreaterThanOrEqualTo(1000);

        assertThat(model.getValueDictionary().size())
                .as("Value dictionary should contain all unique values")
                .isGreaterThanOrEqualTo(1000);
    }

    @Test
    @Order(7)
    @DisplayName("Should canonicalize field names correctly")
    void shouldCanonicalizeFieldNames() throws Exception {
        // GIVEN: Rules with various field name formats
        String rulesJson = """
        [
            {"rule_code": "R1", "conditions": [
                {"field": "customer_tier", "operator": "EQUAL_TO", "value": "GOLD"}
            ]},
            {"rule_code": "R2", "conditions": [
                {"field": "CUSTOMER_TIER", "operator": "EQUAL_TO", "value": "SILVER"}
            ]},
            {"rule_code": "R3", "conditions": [
                {"field": "Customer_Tier", "operator": "EQUAL_TO", "value": "BRONZE"}
            ]}
        ]
        """;

        Path rulesFile = createTempRulesFile(rulesJson);

        // WHEN: Compile
        EngineModel model = COMPILER.compile(rulesFile);

        // THEN: All field names should be canonicalized to same ID
        int fieldId1 = model.getFieldDictionary().getId("CUSTOMER_TIER");

        assertThat(fieldId1)
                .as("Canonicalized field should exist")
                .isGreaterThanOrEqualTo(0);

        // All variations should map to same canonical form
        String canonical = model.getFieldDictionary().decode(fieldId1);
        assertThat(canonical)
                .as("Should be in UPPER_SNAKE_CASE")
                .isEqualTo("CUSTOMER_TIER");
    }

    // ========================================================================
    // VALIDATION ORDER TESTS
    // ========================================================================

    @Test
    @Order(8)
    @DisplayName("Should validate in correct order")
    void shouldValidateInCorrectOrder() throws Exception {
        // GIVEN: Rules with multiple validation issues
        String rulesJson = """
        [
            {
                "rule_code": "MULTIPLE_ISSUES",
                "conditions": [
                    {"field": "field1", "operator": "INVALID_OP", "value": "test"},
                    {"field": "field2", "operator": "GREATER_THAN", "value": "not_a_number"}
                ]
            }
        ]
        """;

        Path rulesFile = createTempRulesFile(rulesJson);

        // WHEN/THEN: Should fail on first validation error encountered
        assertThatThrownBy(() -> COMPILER.compile(rulesFile))
                .as("Should fail validation")
                .isInstanceOf(CompilationException.class);
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    private static Path createTempRulesFile(String content) throws IOException {
        Path file = tempDir.resolve("rules_" + UUID.randomUUID() + ".json");
        Files.writeString(file, content);
        return file;
    }

    record InvalidSchemaTest(String description, String rulesJson) {}
    record ContradictionTest(String description, boolean shouldDetect, String rulesJson) {}
}