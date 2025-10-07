// File: src/main/java/com/google/ruleengine/model/Rule.java
package com.helios.ruleengine.model;

import it.unimi.dsi.fastutil.ints.IntList;

import java.util.Objects;

/**
 * Represents a compiled rule with its predicates and metadata.
 * Uses IntList for memory efficiency.
 */
public final class Rule {
    private final int id;
    private final String ruleCode;
    private final int predicateCount;  // The 'needs' value for counter-based matching
    private final IntList predicateIds;  // References to predicate registry
    private final int priority;
    private final String description;

    public Rule(int id, String ruleCode, int predicateCount,
                IntList predicateIds, int priority, String description) {
        this.id = id;
        this.ruleCode = Objects.requireNonNull(ruleCode);
        this.predicateCount = predicateCount;
        this.predicateIds = predicateIds; // Already an efficient primitive list
        this.priority = priority;
        this.description = description;
    }

    public int getId() { return id; }
    public String getRuleCode() { return ruleCode; }
    public int getPredicateCount() { return predicateCount; }
    public IntList getPredicateIds() { return predicateIds; }
    public int getPriority() { return priority; }
    public String getDescription() { return description; }

    @Override
    public String toString() {
        return String.format("Rule[id=%d, code=%s, needs=%d, priority=%d]",
                id, ruleCode, predicateCount, priority);
    }
}
