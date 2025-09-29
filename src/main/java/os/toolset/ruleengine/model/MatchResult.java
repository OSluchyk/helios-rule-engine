// File: src/main/java/com/google/ruleengine/model/MatchResult.java
package os.toolset.ruleengine.model;


import java.util.List;

/**
 * Result of rule evaluation for an event.
 */
public record MatchResult(
        String eventId,
        List<MatchedRule> matchedRules,
        long evaluationTimeNanos,
        int predicatesEvaluated,
        int rulesEvaluated
) {
    public record MatchedRule(
            int ruleId,
            String ruleCode,
            int priority,
            String description
    ) {
    }
}
