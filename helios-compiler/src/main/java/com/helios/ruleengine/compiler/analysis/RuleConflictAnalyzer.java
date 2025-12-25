/*
 * Copyright (c) 2025 Helios Rule Engine
 * Licensed under the Apache License, Version 2.0
 */
package com.helios.ruleengine.compiler.analysis;

import com.helios.ruleengine.runtime.model.EngineModel;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Analyzes compiled rules for conflicts, overlaps, and redundancies.
 *
 * <p>This analyzer performs post-compilation analysis to detect rules with
 * overlapping conditions that may cause ambiguity or unexpected behavior.
 *
 * <h2>Conflict Detection</h2>
 * <p>Rules are considered to conflict when they:
 * <ul>
 *   <li>Share more than 50% of their predicates (Jaccard similarity > 0.5)</li>
 *   <li>Have different priorities but similar conditions</li>
 *   <li>May match the same events with different outcomes</li>
 * </ul>
 *
 * <h2>Performance Note</h2>
 * <p>Conflict analysis is O(N²) where N is the number of combinations.
 * For large rule sets (>10K rules), this can take several seconds.
 * It is recommended to run this analysis:
 * <ul>
 *   <li>On-demand via UI (not during every compilation)</li>
 *   <li>As part of CI/CD validation for rule changes</li>
 *   <li>Periodically (e.g., nightly) for monitoring</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>
 * EngineModel model = compiler.compile(rulesPath);
 * RuleConflictAnalyzer analyzer = new RuleConflictAnalyzer();
 * ConflictReport report = analyzer.analyzeConflicts(model);
 *
 * if (!report.conflicts().isEmpty()) {
 *     System.out.println("Found " + report.conflicts().size() + " conflicts");
 *     for (RuleConflict conflict : report.conflicts()) {
 *         System.out.printf("%s ↔ %s: %.1f%% overlap%n",
 *             conflict.ruleCode1(),
 *             conflict.ruleCode2(),
 *             conflict.overlapPercentage() * 100);
 *     }
 * }
 * </pre>
 */
public class RuleConflictAnalyzer {

    /**
     * Minimum Jaccard similarity to report as a conflict (default: 0.5 = 50%).
     */
    private static final double DEFAULT_CONFLICT_THRESHOLD = 0.5;

    private final double conflictThreshold;

    /**
     * Creates a conflict analyzer with the default threshold (50%).
     */
    public RuleConflictAnalyzer() {
        this(DEFAULT_CONFLICT_THRESHOLD);
    }

    /**
     * Creates a conflict analyzer with a custom threshold.
     *
     * @param conflictThreshold Minimum Jaccard similarity to report (0.0-1.0)
     */
    public RuleConflictAnalyzer(double conflictThreshold) {
        if (conflictThreshold < 0.0 || conflictThreshold > 1.0) {
            throw new IllegalArgumentException(
                "Conflict threshold must be between 0.0 and 1.0, got: " + conflictThreshold);
        }
        this.conflictThreshold = conflictThreshold;
    }

    /**
     * Analyzes the engine model for rule conflicts.
     *
     * <p><b>Performance:</b> O(N²) complexity where N = number of combinations.
     * For 1000 combinations, expect ~500K comparisons (~100-500ms).
     * For 10,000 combinations, expect ~50M comparisons (~10-50 seconds).
     *
     * @param model The compiled engine model
     * @return Conflict report with detected conflicts
     */
    public ConflictReport analyzeConflicts(EngineModel model) {
        List<RuleConflict> conflicts = new ArrayList<>();

        int numCombinations = model.getNumRules();

        // Pairwise comparison of all combinations
        for (int i = 0; i < numCombinations; i++) {
            for (int j = i + 1; j < numCombinations; j++) {
                RuleConflict conflict = checkConflict(model, i, j);
                if (conflict != null) {
                    conflicts.add(conflict);
                }
            }
        }

        return new ConflictReport(conflicts, conflictThreshold);
    }

    /**
     * Checks if two combinations conflict.
     *
     * @param model The engine model
     * @param combId1 First combination ID
     * @param combId2 Second combination ID
     * @return RuleConflict if they conflict, null otherwise
     */
    private RuleConflict checkConflict(EngineModel model, int combId1, int combId2) {
        IntList predicates1 = model.getCombinationPredicateIds(combId1);
        IntList predicates2 = model.getCombinationPredicateIds(combId2);

        // Calculate Jaccard similarity: |A ∩ B| / |A ∪ B|
        IntSet set1 = new IntOpenHashSet(predicates1);
        IntSet set2 = new IntOpenHashSet(predicates2);

        IntSet intersection = new IntOpenHashSet(set1);
        intersection.retainAll(set2);

        IntSet union = new IntOpenHashSet(set1);
        union.addAll(set2);

        double similarity = union.isEmpty() ? 0.0 :
            (double) intersection.size() / union.size();

        // Report conflicts above threshold
        if (similarity >= conflictThreshold) {
            String rule1 = model.getCombinationRuleCode(combId1);
            String rule2 = model.getCombinationRuleCode(combId2);
            int priority1 = model.getCombinationPriority(combId1);
            int priority2 = model.getCombinationPriority(combId2);

            return new RuleConflict(
                rule1, rule2,
                similarity,
                priority1, priority2,
                intersection.size(),
                set1.size() - intersection.size(),
                set2.size() - intersection.size()
            );
        }

        return null;
    }

    /**
     * Report containing detected rule conflicts.
     *
     * @param conflicts List of detected conflicts
     * @param threshold The similarity threshold used for detection
     */
    public record ConflictReport(
        List<RuleConflict> conflicts,
        double threshold
    ) implements Serializable {

        /**
         * Returns true if any conflicts were detected.
         */
        public boolean hasConflicts() {
            return !conflicts.isEmpty();
        }

        /**
         * Returns the number of conflicts detected.
         */
        public int conflictCount() {
            return conflicts.size();
        }

        /**
         * Returns conflicts sorted by overlap percentage (descending).
         */
        public List<RuleConflict> sortedByOverlap() {
            return conflicts.stream()
                .sorted((a, b) -> Double.compare(b.overlapPercentage(), a.overlapPercentage()))
                .toList();
        }

        /**
         * Returns conflicts where rules have different priorities.
         */
        public List<RuleConflict> differentPriorityConflicts() {
            return conflicts.stream()
                .filter(c -> c.priority1() != c.priority2())
                .toList();
        }
    }

    /**
     * Represents a conflict between two rules.
     *
     * @param ruleCode1 First rule code
     * @param ruleCode2 Second rule code
     * @param overlapPercentage Jaccard similarity (0.0-1.0)
     * @param priority1 Priority of first rule
     * @param priority2 Priority of second rule
     * @param sharedConditions Number of shared predicates
     * @param uniqueToRule1 Number of predicates unique to first rule
     * @param uniqueToRule2 Number of predicates unique to second rule
     */
    public record RuleConflict(
        String ruleCode1,
        String ruleCode2,
        double overlapPercentage,
        int priority1,
        int priority2,
        int sharedConditions,
        int uniqueToRule1,
        int uniqueToRule2
    ) implements Serializable {

        /**
         * Returns a human-readable description of this conflict.
         */
        public String describe() {
            return String.format(
                "Rules '%s' (pri=%d) and '%s' (pri=%d) overlap by %.1f%% " +
                "(%d shared, %d + %d unique)",
                ruleCode1, priority1,
                ruleCode2, priority2,
                overlapPercentage * 100,
                sharedConditions,
                uniqueToRule1, uniqueToRule2
            );
        }

        /**
         * Returns true if the rules have different priorities.
         * This indicates potential ambiguity in rule resolution.
         */
        public boolean hasPriorityConflict() {
            return priority1 != priority2;
        }

        /**
         * Returns the severity level of this conflict.
         *
         * @return "HIGH" if >80% overlap, "MEDIUM" if >60%, "LOW" otherwise
         */
        public String severity() {
            if (overlapPercentage > 0.8) return "HIGH";
            if (overlapPercentage > 0.6) return "MEDIUM";
            return "LOW";
        }
    }
}
