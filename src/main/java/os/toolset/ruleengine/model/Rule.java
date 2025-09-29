// File: src/main/java/com/google/ruleengine/model/Rule.java
package os.toolset.ruleengine.model;

import java.util.List;
import java.util.Objects;

/**
 * Represents a compiled rule with its predicates and metadata.
 */
public final class Rule {
    private final int id;
    private final String ruleCode;
    private final int predicateCount;  // The 'needs' value for counter-based matching
    private final List<Integer> predicateIds;  // References to predicate registry
    private final int priority;
    private final String description;

    public Rule(int id, String ruleCode, int predicateCount,
                List<Integer> predicateIds, int priority, String description) {
        this.id = id;
        this.ruleCode = Objects.requireNonNull(ruleCode);
        this.predicateCount = predicateCount;
        this.predicateIds = List.copyOf(predicateIds);
        this.priority = priority;
        this.description = description;
    }

    public int getId() { return id; }
    public String getRuleCode() { return ruleCode; }
    public int getPredicateCount() { return predicateCount; }
    public List<Integer> getPredicateIds() { return predicateIds; }
    public int getPriority() { return priority; }
    public String getDescription() { return description; }

    @Override
    public String toString() {
        return String.format("Rule[id=%d, code=%s, needs=%d, priority=%d]",
                id, ruleCode, predicateCount, priority);
    }
}
