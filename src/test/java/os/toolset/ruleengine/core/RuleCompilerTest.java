package os.toolset.ruleengine.core;

import it.unimi.dsi.fastutil.ints.IntList;
import org.junit.jupiter.api.*;
import os.toolset.ruleengine.model.Predicate;
import os.toolset.ruleengine.model.Rule;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

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

    private Path writeRules(String json) throws IOException {
        Path rulesFile = tempDir.resolve("rules.json");
        Files.writeString(rulesFile, json);
        return rulesFile;
    }

    @Test
    @DisplayName("Should compile EQUAL_TO and IS_ANY_OF rules successfully")
    void testSuccessfulCompilation() throws Exception {
        String rulesJson = """
            [
                {
                    "rule_code": "RULE_001",
                    "conditions": [
                        {"field": "status", "operator": "EQUAL_TO", "value": "ACTIVE"}
                    ]
                },
                {
                    "rule_code": "RULE_002",
                    "conditions": [
                        {"field": "region", "operator": "IS_ANY_OF", "value": ["NA", "EU"]}
                    ]
                }
            ]
            """;
        Path rulesFile = writeRules(rulesJson);
        EngineModel model = compiler.compile(rulesFile);

        assertThat(model.getNumRules()).isEqualTo(3); // 1 from RULE_001, 2 from RULE_002
        assertThat(model.getPredicateRegistry()).hasSize(3);
    }

    @Test
    @DisplayName("Should expand rules with multiple IS_ANY_OF conditions")
    void testDnfExpansion() throws Exception {
        String rulesJson = """
            [
                {
                    "rule_code": "COMPLEX_EXPANSION",
                    "conditions": [
                        {"field": "status", "operator": "EQUAL_TO", "value": "ACTIVE"},
                        {"field": "tier", "operator": "IS_ANY_OF", "value": ["GOLD", "PLATINUM"]},
                        {"field": "device", "operator": "IS_ANY_OF", "value": ["MOBILE", "DESKTOP"]}
                    ]
                }
            ]
            """;
        Path rulesFile = writeRules(rulesJson);
        EngineModel model = compiler.compile(rulesFile);

        // 1 (status) * 2 (tier) * 2 (device) = 4 internal rules
        assertThat(model.getNumRules()).isEqualTo(4);
        assertThat(model.getRulesByCode("COMPLEX_EXPANSION")).hasSize(4);

        // Check that all rules have the same static predicate
        int fieldId = model.getFieldDictionary().getId("STATUS");
        int valueId = model.getValueDictionary().getId("ACTIVE");
        int staticPredicateId = model.getPredicateId(new Predicate(fieldId, Predicate.Operator.EQUAL_TO, valueId));


        // After the fix, this lookup should succeed
        assertThat(staticPredicateId).isNotEqualTo(-1);

        for (int i = 0; i < model.getNumRules(); i++) {
            if (model.getRuleCode(i).equals("COMPLEX_EXPANSION")) {
                IntList predicateIds = model.getRule(i).getPredicateIds();
                assertThat(new ArrayList<>(predicateIds)).contains(staticPredicateId);
                assertThat(model.getRule(i).getPredicateCount()).isEqualTo(3);
            }
        }
    }

    @Test
    @DisplayName("Should reuse atomic predicates from different IS_ANY_OF (Smart Factoring)")
    void testSmartIsAnyOfFactoringEffect() throws Exception {
        String rulesJson = """
        [
            {
                "rule_code": "US_CUSTOMERS",
                "conditions": [
                    {"field": "type", "operator": "EQUAL_TO", "value": "A"},
                    {"field": "state", "operator": "IS_ANY_OF", "value": ["CA", "TX"]}
                ]
            },
            {
                "rule_code": "ALL_CUSTOMERS",
                "conditions": [
                    {"field": "type", "operator": "EQUAL_TO", "value": "B"},
                    {"field": "state", "operator": "IS_ANY_OF", "value": ["CA", "WA", "TX", "FL"]}
                ]
            }
        ]
        """;
        Path rulesFile = writeRules(rulesJson);
        EngineModel model = compiler.compile(rulesFile);

        // Total internal rules: 2 (from US_CUSTOMERS) + 4 (from ALL_CUSTOMERS) = 6
        assertThat(model.getNumRules()).isEqualTo(6);

        // Total unique predicates should be 6, not 8.
        // type=A, type=B, state=CA, state=TX, state=WA, state=FL
        // The predicates for CA and TX are shared.
        assertThat(model.getPredicateRegistry()).hasSize(6);

        // Verify that the predicate IDs for "CA" and "TX" are reused.
        int fieldId = model.getFieldDictionary().getId("STATE");
        int caValueId = model.getValueDictionary().getId("CA");
        int txValueId = model.getValueDictionary().getId("TX");

        int caPredicateId = model.getPredicateId(new Predicate(fieldId, Predicate.Operator.EQUAL_TO, caValueId));
        int txPredicateId = model.getPredicateId(new Predicate(fieldId, Predicate.Operator.EQUAL_TO, txValueId));

        // After the fix, these lookups should succeed
        assertThat(caPredicateId).isNotEqualTo(-1);
        assertThat(txPredicateId).isNotEqualTo(-1);

        // Find all predicate IDs for the first logical rule
        List<List<Integer>> rule1PredicateIdSets = model.getRulesByCode("US_CUSTOMERS").stream()
                .map(Rule::getPredicateIds)
                .map(ArrayList::new) // Convert to standard list for assertion
                .collect(Collectors.toList());

        // Find all predicate IDs for the second logical rule
        List<List<Integer>> rule2PredicateIdSets = model.getRulesByCode("ALL_CUSTOMERS").stream()
                .map(Rule::getPredicateIds)
                .map(ArrayList::new) // Convert to standard list for assertion
                .collect(Collectors.toList());

        // Assert that the shared predicate IDs appear in both sets of expanded rules
        assertThat(rule1PredicateIdSets).anySatisfy(ids -> assertThat(ids).contains(caPredicateId));
        assertThat(rule1PredicateIdSets).anySatisfy(ids -> assertThat(ids).contains(txPredicateId));
        assertThat(rule2PredicateIdSets).anySatisfy(ids -> assertThat(ids).contains(caPredicateId));
        assertThat(rule2PredicateIdSets).anySatisfy(ids -> assertThat(ids).contains(txPredicateId));
    }


    @Test
    @DisplayName("Should handle all advanced operators correctly")
    void testAdvancedOperators() throws Exception {
        String rulesJson = """
            [
                {"rule_code": "GT_RULE", "conditions": [{"field": "amount", "operator": "GREATER_THAN", "value": 100}]},
                {"rule_code": "LT_RULE", "conditions": [{"field": "amount", "operator": "LESS_THAN", "value": 100}]},
                {"rule_code": "BT_RULE", "conditions": [{"field": "amount", "operator": "BETWEEN", "value": [50, 150]}]},
                {"rule_code": "RX_RULE", "conditions": [{"field": "code", "operator": "REGEX", "value": "A.C"}]},
                {"rule_code": "CN_RULE", "conditions": [{"field": "code", "operator": "CONTAINS", "value": "B"}]}
            ]
            """;
        Path rulesFile = writeRules(rulesJson);
        EngineModel model = compiler.compile(rulesFile);

        assertThat(model.getNumRules()).isEqualTo(5);
        assertThat(model.getPredicateRegistry()).hasSize(5);
        assertThat(model.getRulesByCode("GT_RULE")).isNotNull();
    }


    @Test
    @DisplayName("Should reject IS_ANY_OF with non-list value")
    void testIsAnyOfWithNonList() throws IOException {
        String rulesJson = """
            [
                {
                    "rule_code": "RULE_001",
                    "conditions": [
                        {"field": "region", "operator": "IS_ANY_OF", "value": "NA"}
                    ]
                }
            ]
            """;
        Path rulesFile = writeRules(rulesJson);
        assertThatThrownBy(() -> compiler.compile(rulesFile))
                .isInstanceOf(RuleCompiler.CompilationException.class)
                .hasMessageContaining("Value for IS_ANY_OF must be a list");
    }

    @Test
    @DisplayName("Should perform strength reduction for IS_ANY_OF with single value")
    void testStrengthReduction() throws Exception {
        String rulesJson = """
        [
            {
                "rule_code": "SINGLE_VALUE_IN_LIST",
                "conditions": [
                    {"field": "status", "operator": "IS_ANY_OF", "value": ["ACTIVE"]}
                ]
            }
        ]
        """;
        Path rulesFile = writeRules(rulesJson);
        EngineModel model = compiler.compile(rulesFile);

        // Should compile to a single rule with a single predicate, not expanded
        assertThat(model.getNumRules()).isEqualTo(1);
        Rule rule = model.getRule(0);
        assertThat(rule.getPredicateCount()).isEqualTo(1);

        // The predicate should be a simple EQUAL_TO predicate
        int predicateId = rule.getPredicateIds().getInt(0);
        Predicate p = model.getPredicate(predicateId);

        int fieldId = model.getFieldDictionary().getId("STATUS");
        int valueId = model.getValueDictionary().getId("ACTIVE");

        assertThat(p.fieldId()).isEqualTo(fieldId);
        assertThat(p.operator()).isEqualTo(Predicate.Operator.EQUAL_TO);
        assertThat(p.value()).isEqualTo(valueId);
    }

    @Test
    @DisplayName("Should skip disabled rules")
    void testDisabledRules() throws Exception {
        String rulesJson = """
            [
                {
                    "rule_code": "ENABLED_RULE",
                    "conditions": [{"field": "status", "operator": "EQUAL_TO", "value": "ACTIVE"}]
                },
                {
                    "rule_code": "DISABLED_RULE",
                    "conditions": [{"field": "status", "operator": "EQUAL_TO", "value": "INACTIVE"}],
                    "enabled": false
                }
            ]
            """;
        Path rulesFile = writeRules(rulesJson);
        EngineModel model = compiler.compile(rulesFile);

        assertThat(model.getNumRules()).isEqualTo(1);
        assertThat(model.getRulesByCode("ENABLED_RULE")).isNotNull();
        assertThat(model.getRulesByCode("DISABLED_RULE")).isNull();
    }
}