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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuleCompilerTest {

    private RuleCompiler compiler;
    private Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        // Initialize the compiler with a tracer
        compiler = new RuleCompiler(TracingService.getInstance().getTracer());
        tempDir = Files.createTempDirectory("rule_engine_test");
    }

    @AfterEach
    void tearDown() throws IOException {
        // Clean up the temporary directory
        Files.walk(tempDir)
                .sorted(java.util.Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(java.io.File::delete);
    }

    /**
     * Helper method to write a JSON string to a temporary rules file.
     */
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

        // --- Explanation ---
        // RULE_A expands to 2 combinations:
        //   1. { status=ACTIVE, country=US }
        //   2. { status=ACTIVE, country=CA }
        // RULE_B expands to 2 combinations:
        //   3. { status=ACTIVE, country=US }  (Deduplicated, matches Combo 1)
        //   4. { status=ACTIVE, country=UK }
        //
        // Total expanded combinations = 4
        // Total unique combinations = 3 (Combo 1, Combo 2, Combo 4)

        Map<String, Object> metadata = model.getStats().metadata();
        assertThat(metadata.get("logicalRules")).isEqualTo(2);
        assertThat(metadata.get("totalExpandedCombinations")).isEqualTo(4);
        assertThat(metadata.get("uniqueCombinations")).isEqualTo(3);
        assertThat(metadata.get("deduplicationRatePercent")).isEqualTo(25.0);
        assertThat(model.getNumRules()).isEqualTo(3); // numRules is unique combinations


        // Find the predicate IDs for the shared combination
        int statusFieldId = model.getFieldDictionary().getId("STATUS");
        int countryFieldId = model.getFieldDictionary().getId("COUNTRY");
        int activeValueId = model.getValueDictionary().getId("ACTIVE");
        int usValueId = model.getValueDictionary().getId("US");

        Predicate p1 = null, p2 = null;
        for (Predicate p : model.getUniquePredicates()) {
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

        // This is the canonical key for the shared combination { status=ACTIVE, country=US }
        IntList sharedCombinationKey = new IntArrayList(new int[]{p1Id, p2Id});
        sharedCombinationKey.sort(null);

        // Verify this shared combination exists
        boolean foundSharedCombination = false;
        for (int i = 0; i < model.getNumRules(); i++) {
            IntList combinationPreds = new IntArrayList(model.getCombinationPredicateIds(i));
            combinationPreds.sort(null);
            if (combinationPreds.equals(sharedCombinationKey)) {
                foundSharedCombination = true;
                // Verify that this combination is mapped to both rules
                List<String> ruleCodes = model.getCombinationRuleCodes(i);
                assertThat(ruleCodes).containsExactlyInAnyOrder("RULE_A", "RULE_B");
                break;
            }
        }
        assertThat(foundSharedCombination).isTrue();
    }

    // --- NEW TEST ADDED ---
    @Test
    @DisplayName("Should apply IS_ANY_OF factorization during compilation")
    void testCompilerAppliesIsAnyOfFactorization() throws Exception {
        String rulesJson = """
                [
                  {
                    "rule_code": "RULE_A",
                    "conditions": [
                      {"field": "amount", "operator": "GREATER_THAN", "value": 10},
                      {"field": "country", "operator": "IS_ANY_OF", "value": ["US", "CA", "UK"]}
                    ]
                  },
                  {
                    "rule_code": "RULE_B",
                    "conditions": [
                      {"field": "amount", "operator": "GREATER_THAN", "value": 10},
                      {"field": "country", "operator": "IS_ANY_OF", "value": ["US", "CA", "MX"]}
                    ]
                  }
                ]
                """;
        Path rulesFile = writeRules(rulesJson);

        // --- Analysis WITHOUT Factorization ---
        // RULE_A expands to 3 combos:
        //   1. { amount > 10, country=US }
        //   2. { amount > 10, country=CA }
        //   3. { amount > 10, country=UK }
        // RULE_B expands to 3 combos:
        //   4. { amount > 10, country=US } (Deduplicates to 1)
        //   5. { amount > 10, country=CA } (Deduplicates to 2)
        //   6. { amount > 10, country=MX }
        // Total Expanded: 6, Unique: 4

        // --- Analysis WITH Factorization ---
        // Factorizer rewrites rules to:
        // RULE_A' = { amount > 10, country IS_ANY_OF [US, CA], country == UK }
        // RULE_B' = { amount > 10, country IS_ANY_OF [US, CA], country == MX }
        //
        // Compiler expands RULE_A':
        //   1. { amount > 10, country=US, country=UK }
        //   2. { amount > 10, country=CA, country=UK }
        // Compiler expands RULE_B':
        //   3. { amount > 10, country=US, country=MX }
        //   4. { amount > 10, country=CA, country=MX }
        //
        // Total Expanded: 4, Unique: 4

        // When
        EngineModel model = compiler.compile(rulesFile);

        // Then
        Map<String, Object> metadata = model.getStats().metadata();

        // Verify the stats match the "WITH Factorization" scenario
        assertThat(metadata.get("logicalRules")).isEqualTo(2);
        assertThat(metadata.get("totalExpandedCombinations")).isEqualTo(4);
        assertThat(metadata.get("uniqueCombinations")).isEqualTo(4);
        assertThat(metadata.get("deduplicationRatePercent")).isEqualTo(0.0);
        assertThat(model.getNumRules()).isEqualTo(4);
    }
    // --- END NEW TEST ---

    @Test
    @DisplayName("Should sort predicates by weight (based on selectivity and cost)")
    void shouldSortPredicatesByWeight() throws Exception {
        // Given rules with varying selectivity and operator cost
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
                "  Weight: %.2f (Cost: %.1f, Selectivity: %.3f), Predicate: {field: %s, op: %s, value: %s}%n",
                p.weight(),
                new RuleCompiler.SelectivityProfile(List.of(), null, null).getCost(p.operator()), // Get base cost
                p.selectivity(),
                model.getFieldDictionary().decode(p.fieldId()),
                p.operator(),
                p.value() instanceof Integer ? p.value() : "..." // Avoid printing full regex/etc
        ));

        // Predicates are sorted by ASCENDING weight (cheapest first).
        // Weight = (1.0 - Selectivity) * Cost

        // `status` field appears in 3/3 rules (base selectivity = 1.0)
        // `amount` field appears in 1/3 rules (base selectivity = 0.33)
        // `notes` field appears in 1/3 rules (base selectivity = 0.33)

        // `status` EQUAL_TO (Sel=1.0*0.1=0.1, Cost=1.0) -> Weight=(1-0.1)*1.0 = 0.90
        // `amount` GREATER_THAN (Sel=0.33*0.3=0.1, Cost=1.5) -> Weight=(1-0.1)*1.5 = 1.35
        // `notes` REGEX (Sel=0.33*0.5=0.166, Cost=10.0) -> Weight=(1-0.166)*10.0 = 8.34

        // The EQUAL_TO predicates should be first (lowest weight ~0.90)
        assertThat(sortedPredicates.get(0).operator()).isEqualTo(Predicate.Operator.EQUAL_TO);
        assertThat(sortedPredicates.get(1).operator()).isEqualTo(Predicate.Operator.EQUAL_TO);

        // The GREATER_THAN predicate should be in the middle (moderate weight ~1.35)
        assertThat(sortedPredicates.get(2).operator()).isEqualTo(Predicate.Operator.GREATER_THAN);

        // The REGEX predicate should be last (highest weight ~8.34)
        assertThat(sortedPredicates.get(3).operator()).isEqualTo(Predicate.Operator.REGEX);

        // Verify weights are in ascending order (cheapest to most expensive)
        assertThat(sortedPredicates).isSortedAccordingTo(Comparator.comparing(Predicate::weight));

        // Additional validation: confirm weight ordering makes sense
        float firstWeight = sortedPredicates.get(0).weight();
        float lastWeight = sortedPredicates.get(sortedPredicates.size() - 1).weight();
        assertThat(firstWeight).isLessThan(lastWeight);
    }
}