package com.helios.ruleengine.core.compiler;

import com.helios.ruleengine.core.model.EngineModel;
import com.helios.ruleengine.infrastructure.telemetry.TracingService;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.helios.ruleengine.model.Predicate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

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

        assertThat(model.getStats().metadata().get("uniqueCombinations")).isEqualTo(3);
        assertThat(model.getNumRules()).isEqualTo(3);


        int statusFieldId = model.getFieldDictionary().getId("STATUS");
        int countryFieldId = model.getFieldDictionary().getId("COUNTRY");
        int activeValueId = model.getValueDictionary().getId("ACTIVE");
        int usValueId = model.getValueDictionary().getId("US");

        Predicate p1 = null, p2 = null;
        for (Predicate p : model.getPredicateRegistry().keySet()) {
            if (p.fieldId() == statusFieldId && p.value().equals(activeValueId)) {
                p1 = p;
            }
            if (p.fieldId() == countryFieldId && p.value().equals(usValueId)) {
                p2 = p;
            }
        }
        assertThat(p1).isNotNull();
        assertThat(p2).isNotNull();

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

    @Test
    @DisplayName("Should sort predicates by weight (based on selectivity)")
    void shouldSortPredicatesByWeight() throws Exception {
        // Given rules with varying selectivity
        String rulesJson = """
        [
          {"rule_code": "R1", "conditions": [{"field": "status", "operator": "EQUAL_TO", "value": "ACTIVE"}]},
          {"rule_code": "R2", "conditions": [{"field": "status", "operator": "EQUAL_TO", "value": "INACTIVE"}]},
          {"rule_code": "R3", "conditions": [
            {"field": "status", "operator": "EQUAL_TO", "value": "ACTIVE"},
            {"field": "amount", "operator": "GREATER_THAN", "value": 1000},
            {"field": "notes", "operator": "REGEX", "value": ".*urgent.*"}
          ]}
        ]
        """;
        Path rulesFile = writeRules(rulesJson);

        // When
        EngineModel model = compiler.compile(rulesFile);
        List<Predicate> sortedPredicates = model.getSortedPredicates();

        // Then
        assertThat(sortedPredicates).isNotEmpty();
        System.out.println("--- Sorted Predicates (cheapest first) ---");
        sortedPredicates.forEach(p -> System.out.printf(
                "  Weight: %.2f, Selectivity: %.2f, Predicate: {field: %s, op: %s, value: %s}%n",
                p.weight(), p.selectivity(), model.getFieldDictionary().decode(p.fieldId()), p.operator(), p.value()
        ));

        // The REGEX predicate should be first (lowest weight because lowest selectivity)
        assertThat(sortedPredicates.get(0).operator()).isEqualTo(Predicate.Operator.REGEX);
        // The GREATER_THAN predicate should be next
        assertThat(sortedPredicates.get(1).operator()).isEqualTo(Predicate.Operator.GREATER_THAN);
        // The EQUAL_TO predicates are last
        assertThat(sortedPredicates.get(2).operator()).isEqualTo(Predicate.Operator.EQUAL_TO);
        assertThat(sortedPredicates.get(3).operator()).isEqualTo(Predicate.Operator.EQUAL_TO);

        // Verify weights are in ascending order
        assertThat(sortedPredicates).isSortedAccordingTo(Comparator.comparing(Predicate::weight));
    }
}