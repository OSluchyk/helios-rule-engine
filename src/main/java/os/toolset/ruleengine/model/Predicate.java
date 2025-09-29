// File: src/main/java/com/google/ruleengine/model/Predicate.java
package os.toolset.ruleengine.model;


import java.util.Objects;

/**
 * Immutable atomic condition that evaluates to true/false.
 * Represents field-value pairs for rule matching.
 */
public record Predicate(String field, Object value) {

    public Predicate {
        Objects.requireNonNull(field, "Field cannot be null");
        Objects.requireNonNull(value, "Value cannot be null");
    }

    /**
     * Evaluates this predicate against an event's field value.
     */
    public boolean evaluate(Object eventValue) {
        if (eventValue == null) return false;

        // Type-safe comparison
        if (value instanceof Number && eventValue instanceof Number) {
            return ((Number) value).doubleValue() == ((Number) eventValue).doubleValue();
        }
        return value.equals(eventValue);
    }

    @Override
    public String toString() {
        return field + "=" + value;
    }
}


