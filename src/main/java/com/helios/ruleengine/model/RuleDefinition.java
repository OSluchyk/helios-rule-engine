// File: src/main/java/com/google/ruleengine/model/RuleDefinition.java
package com.helios.ruleengine.model;



import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * JSON representation of a rule for deserialization.
 */
public record RuleDefinition(
        @JsonProperty("rule_code") String ruleCode,
        @JsonProperty("conditions") List<Condition> conditions,
        @JsonProperty("priority") Integer priority,
        @JsonProperty("description") String description,
        @JsonProperty("enabled") Boolean enabled
) {
    public record Condition(
            @JsonProperty("field") String field,
            @JsonProperty("operator") String operator,
            @JsonProperty("value") Object value
    ) {}

    // Default values
    public Integer priority() {
        return priority != null ? priority : 0;
    }

    public Boolean enabled() {
        return enabled != null ? enabled : true;
    }
}

