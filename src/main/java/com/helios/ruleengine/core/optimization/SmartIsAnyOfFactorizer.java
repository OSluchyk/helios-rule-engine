/*
 * Copyright (c) 2025 Helios Rule Engine
 * Licensed under the Apache License, Version 2.0
 */
package com.helios.ruleengine.core.optimization;

import com.helios.ruleengine.model.RuleDefinition;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Optimizes rules by factoring out common `IS_ANY_OF` predicate subsets.
 *
 * This is a critical compile-time optimization that identifies rules sharing
 * common non-`IS_ANY_OF` conditions (a "signature") and then finds the
 * largest common subset of values for their `IS_ANY_OF` conditions.
 *
 * For example, given two rules with the same signature:
 * 1. { amount > 10, country IS_ANY_OF [US, CA, UK] }
 * 2. { amount > 10, country IS_ANY_OF [US, CA, MX] }
 *
 * It will identify the common signature { amount > 10 } and the common
 * `IS_ANY_OF` field "country". It finds the largest common subset of values: [US, CA].
 *
 * It then rewrites the rules as:
 * 1. { amount > 10, country IS_ANY_OF [CA, US], country EQUAL_TO UK }
 * 2. { amount > 10, country IS_ANY_OF [CA, US], country EQUAL_TO MX }
 * (Note: The lists [CA, US] are sorted to ensure deterministic output).
 *
 * This allows the compiler's subsequent steps to expand these rules into
 * fewer total combinations.
 */
public class SmartIsAnyOfFactorizer {

    /**
     * A record representing the "signature" of a rule, which includes all
     * conditions *except* for `IS_ANY_OF` operators. Rules with the same
     * signature are candidates for factorization.
     */
    private record RuleSignature(Set<RuleDefinition.Condition> conditions) {
        @Override
        public int hashCode() {
            return Objects.hash(conditions);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            RuleSignature other = (RuleSignature) obj;
            return Objects.equals(conditions, other.conditions);
        }
    }

    /**
     * Internal result class to track the state of a factorization pass.
     */
    private record FactoredGroupResult(List<RuleDefinition> factoredRules, boolean wasFactored) {}

    /**
     * Applies subset factorization to a list of rule definitions.
     * This method will loop, applying one factorization step at a time,
     * until no further optimizations can be found.
     *
     * @param definitions The original list of rule definitions.
     * @return A new list of optimized rule definitions.
     */
    public List<RuleDefinition> factorize(List<RuleDefinition> definitions) {
        boolean rulesChanged = true;
        List<RuleDefinition> currentDefinitions = new ArrayList<>(definitions);

        // Loop until no more optimizations can be applied
        while (rulesChanged) {
            rulesChanged = false;
            List<RuleDefinition> nextDefinitions = new ArrayList<>();

            // Group rules by their "signature" (all non-IS_ANY_OF conditions)
            Map<RuleSignature, List<RuleDefinition>> ruleGroups = currentDefinitions.stream()
                    .collect(Collectors.groupingBy(this::getRuleSignature));

            // Process each group of rules that share a signature
            for (Map.Entry<RuleSignature, List<RuleDefinition>> entry : ruleGroups.entrySet()) {
                List<RuleDefinition> group = entry.getValue();

                // Attempt to factor this group
                FactoredGroupResult result = factorizeGroup(group);
                nextDefinitions.addAll(result.factoredRules());

                if (result.wasFactored()) {
                    rulesChanged = true;
                }
            }
            currentDefinitions = nextDefinitions;
        }
        return currentDefinitions;
    }

    /**
     * Creates a signature for a rule based on its conditions,
     * excluding any IS_ANY_OF operators.
     */
    private RuleSignature getRuleSignature(RuleDefinition rule) {
        if (rule.conditions() == null) {
            return new RuleSignature(Collections.emptySet());
        }
        Set<RuleDefinition.Condition> signatureConditions = rule.conditions().stream()
                .filter(c -> c.operator() != null && !"IS_ANY_OF".equalsIgnoreCase(c.operator()))
                .collect(Collectors.toSet());
        return new RuleSignature(signatureConditions);
    }

    /**
     * Attempts to apply one pass of factorization to a group of rules
     * that share the same signature.
     */
    private FactoredGroupResult factorizeGroup(List<RuleDefinition> group) {
        if (group.size() <= 1) {
            // Cannot factor a group of 0 or 1
            return new FactoredGroupResult(group, false);
        }

        // Find all IS_ANY_OF fields that are present in *every* rule in the group
        String fieldToFactor = findCommonIsAnyOfField(group);
        if (fieldToFactor == null) {
            // No common field to factor
            return new FactoredGroupResult(group, false);
        }

        // Find the largest common subset of values for this field
        Set<Object> commonSubset = findLargestCommonSubset(group, fieldToFactor);

        // Factoring is not beneficial if the common subset is empty or just one item,
        // as `IS_ANY_OF [A]` is equivalent to `EQUAL_TO A`, which the
        // compiler already handles efficiently.
        if (commonSubset.size() <= 1) {
            return new FactoredGroupResult(group, false);
        }

        // We found a beneficial factorization. Rewrite the rules.
        List<RuleDefinition> newRules = new ArrayList<>();
        for (RuleDefinition originalRule : group) {
            newRules.add(rewriteRule(originalRule, fieldToFactor, commonSubset));
        }

        // --- FIX: Check for Infinite Loop ---
        // We must check if the rewritten rules are *actually different* from
        // the original group. If they are identical (using Set equality to
        // ignore list order), it means factorization is complete, and we
        // must return false to prevent an infinite loop.
        boolean wasFactored = !new HashSet<>(group).equals(new HashSet<>(newRules));
        // --- END FIX ---

        return new FactoredGroupResult(newRules, wasFactored);
    }

    /**
     * Rewrites a single rule by replacing its original IS_ANY_OF condition
     * with two new conditions:
     * 1. A new `IS_ANY_OF` condition for the common subset.
     * 2. One or more `EQUAL_TO` or a smaller `IS_ANY_OF` for the remainder.
     */
    private RuleDefinition rewriteRule(RuleDefinition rule, String field, Set<Object> commonSubset) {
        List<RuleDefinition.Condition> newConditions = new ArrayList<>();

        // 1. Add all original conditions *except* the one we are factoring
        for (RuleDefinition.Condition c : rule.conditions()) {
            if (!field.equals(c.field()) || !"IS_ANY_OF".equalsIgnoreCase(c.operator())) {
                newConditions.add(c);
            }
        }

        // 2. Add the new, shared `IS_ANY_OF` condition for the common subset
        // --- FIX: Ensure deterministic order ---
        List<Object> sortedCommonSubset = getSortedList(commonSubset);
        // --- END FIX ---
        newConditions.add(new RuleDefinition.Condition(
                field,
                "IS_ANY_OF",
                sortedCommonSubset // Use the sorted list
        ));

        // 3. Find the remainder values (Original - CommonSubset) and add them back
        //    (Uses the fixed getIsAnyOfValues to get ALL original values)
        Set<Object> originalValues = getIsAnyOfValues(rule, field);
        originalValues.removeAll(commonSubset); // Calculate the remainder

        if (originalValues.isEmpty()) {
            // This rule *only* contained the common subset, so we are done.
        } else if (originalValues.size() == 1) {
            // Remainder is one item, add it as `EQUAL_TO`
            // (No sorting needed for a single item)
            newConditions.add(new RuleDefinition.Condition(
                    field,
                    "EQUAL_TO",
                    originalValues.iterator().next()
            ));
        } else {
            // Remainder is multiple items, add it as a new, smaller `IS_ANY_OF`
            // --- FIX: Ensure deterministic order ---
            List<Object> sortedRemainder = getSortedList(originalValues);
            // --- END FIX ---
            newConditions.add(new RuleDefinition.Condition(
                    field,
                    "IS_ANY_OF",
                    sortedRemainder // Use the sorted list
            ));
        }

        return new RuleDefinition(
                rule.ruleCode(),
                newConditions,
                rule.priority(),
                rule.description(),
                rule.enabled()
        );
    }

    /**
     * Finds the largest common subset of values for a given field
     * across all rules in the group.
     */
    private Set<Object> findLargestCommonSubset(List<RuleDefinition> group, String field) {
        Set<Object> commonSubset = null;
        for (RuleDefinition rule : group) {
            // Uses the fixed getIsAnyOfValues
            Set<Object> currentValues = getIsAnyOfValues(rule, field);
            if (commonSubset == null) {
                // First rule, start with its values
                commonSubset = new HashSet<>(currentValues);
            } else {
                // All other rules, find the intersection
                commonSubset.retainAll(currentValues);
            }
        }
        return (commonSubset == null) ? Collections.emptySet() : commonSubset;
    }

    /**
     * Finds an `IS_ANY_OF` field that is present in all rules in the group.
     *
     * @return The canonical name of a common field, or null if none is found.
     */
    private String findCommonIsAnyOfField(List<RuleDefinition> group) {
        if (group.isEmpty()) return null;

        // Get all IS_ANY_OF fields from the first rule
        Set<String> commonFields = getIsAnyOfFields(group.get(0));
        if (commonFields.isEmpty()) return null;

        // Intersect with fields from all other rules
        for (int i = 1; i < group.size(); i++) {
            commonFields.retainAll(getIsAnyOfFields(group.get(i)));
        }

        // Return the first common field found, if any
        return commonFields.stream().findFirst().orElse(null);
    }

    /**
     * Gets all fields (as canonical strings) used in `IS_ANY_OF`
     * conditions for a single rule.
     */
    private Set<String> getIsAnyOfFields(RuleDefinition rule) {
        if (rule.conditions() == null) return Collections.emptySet();
        return rule.conditions().stream()
                .filter(c -> "IS_ANY_OF".equalsIgnoreCase(c.operator()) && c.field() != null)
                .map(RuleDefinition.Condition::field)
                .collect(Collectors.toSet());
    }

    /**
     * Gets the set of values for a specific `IS_ANY_OF` field in a rule.
     *
     * This method aggregates values from *all* IS_ANY_OF conditions
     * for the given field, not just the first one. This correctly handles
     * rules rewritten by a previous pass.
     */
    private Set<Object> getIsAnyOfValues(RuleDefinition rule, String field) {
        if (rule.conditions() == null) return Collections.emptySet();

        Set<Object> values = new HashSet<>();
        for (RuleDefinition.Condition c : rule.conditions()) {
            if (field.equals(c.field()) && "IS_ANY_OF".equalsIgnoreCase(c.operator())) {
                if (c.value() instanceof Collection) {
                    values.addAll((Collection<?>) c.value());
                }
            }
        }
        return values;
    }

    /**
     * --- NEW HELPER METHOD ---
     * Converts a Set<Object> into a deterministically sorted List<Object>.
     *
     * It first attempts to sort based on natural order (Comparable).
     * If that fails (e.g., mixed types, non-Comparable objects),
     * it falls back to sorting based on the object's String representation.
     * This guarantees a stable order for compilation.
     *
     * @param originalSet The set to sort.
     * @return A new, deterministically sorted List.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<Object> getSortedList(Set<Object> originalSet) {
        List<Object> sortedList = new ArrayList<>(originalSet);
        try {
            // Try to sort using natural order
            Collections.sort((List<Comparable>) (List<?>) sortedList);
        } catch (Exception e) {
            // Fallback: If types are mixed or not Comparable,
            // sort by string representation to ensure a stable order.
            sortedList.sort(Comparator.comparing(Object::toString));
        }
        return sortedList;
    }
}