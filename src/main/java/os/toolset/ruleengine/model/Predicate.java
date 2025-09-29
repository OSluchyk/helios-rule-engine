// File: src/main/java/com/google/ruleengine/model/Predicate.java
package os.toolset.ruleengine.model;


import org.apache.commons.lang3.math.NumberUtils;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable atomic condition with an operator.
 * This record is self-validating through its compact constructor.
 */
public record Predicate(String field, Operator operator, Object value, Pattern pattern) {

    public enum Operator {
        EQUAL_TO, IS_ANY_OF, // Handled by compiler expansion
        GREATER_THAN, LESS_THAN, BETWEEN, CONTAINS, REGEX;

        public static Operator fromString(String text) {
            if (text == null) return null;
            try {
                return Operator.valueOf(text.toUpperCase());
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /**
     * Compact constructor for validation. This code runs BEFORE the record's fields are assigned.
     */
    public Predicate {
        Objects.requireNonNull(field, "Field cannot be null");
        Objects.requireNonNull(operator, "Operator cannot be null");
        Objects.requireNonNull(value, "Value cannot be null");

        // Operator-specific validation for type safety
        switch (operator) {
            case GREATER_THAN, LESS_THAN:
                if (!(value instanceof Number)) {
                    throw new IllegalArgumentException("Value for " + operator + " must be a Number.");
                }
                break;
            case BETWEEN:
                if (!(value instanceof List<?> list) || list.size() != 2 || !(list.get(0) instanceof Number) || !(list.get(1) instanceof Number)) {
                    throw new IllegalArgumentException("Value for BETWEEN must be a List of two Numbers.");
                }
                break;
            case CONTAINS:
                if (!(value instanceof String)) {
                    throw new IllegalArgumentException("Value for CONTAINS must be a String.");
                }
                break;
            case REGEX:
                if (!(value instanceof String)) {
                    throw new IllegalArgumentException("Value for REGEX must be a String.");
                }
                // Pre-compile the pattern
                try {
                    pattern = Pattern.compile((String) value);
                } catch (Exception e) {
                    throw new IllegalArgumentException("Invalid regex pattern: " + value, e);
                }
                break;
            default:
                // No specific validation needed for EQUAL_TO or IS_ANY_OF
                break;
        }
    }


    /**
     * Convenience constructor that derives the pattern automatically.
     */
    public Predicate(String field, Operator operator, Object value) {
        this(field, operator, value, null); // Pattern is calculated in the compact constructor
    }

    public boolean evaluate(Object eventValue) {
        if (eventValue == null) return false;

        switch (operator) {
            case EQUAL_TO:
                return isEqual(eventValue);
            case GREATER_THAN:
                return isGreaterThan(eventValue);
            case LESS_THAN:
                return isLessThan(eventValue);
            case BETWEEN:
                return isBetween(eventValue);
            case CONTAINS:
                // The compact constructor guarantees value is a String, so this is safe.
                return (eventValue instanceof String) && ((String) eventValue).contains((String) value);
            case REGEX:
                // The pattern is guaranteed to be non-null and compiled by the constructor.
                return (eventValue instanceof String) && pattern.matcher((String) eventValue).matches();
            default: // IS_ANY_OF is expanded, should not be evaluated at runtime
                return false;
        }
    }

    private boolean isEqual(Object eventValue) {
        if (value instanceof Number && eventValue instanceof Number) {
            return Double.compare(((Number) value).doubleValue(), ((Number) eventValue).doubleValue()) == 0;
        }
        return value.equals(eventValue);
    }

    private boolean isGreaterThan(Object eventValue) {
        // Validation in constructor guarantees these are Numbers
        if (eventValue instanceof Number) {
            return Double.compare(((Number) eventValue).doubleValue(), ((Number) value).doubleValue()) > 0;
        }
        return false;
    }

    private boolean isLessThan(Object eventValue) {
        // Validation in constructor guarantees these are Numbers
        if (eventValue instanceof Number) {
            return Double.compare(((Number) eventValue).doubleValue(), ((Number) value).doubleValue()) < 0;
        }
        return false;
    }

    private boolean isBetween(Object eventValue) {
        // Validation in constructor guarantees types and size
        if (eventValue instanceof Number) {
            List<?> range = (List<?>) value;
            double val = ((Number) eventValue).doubleValue();
            double lower = ((Number) range.get(0)).doubleValue();
            double upper = ((Number) range.get(1)).doubleValue();
            return val >= lower && val <= upper;
        }
        return false;
    }


    @Override
    public String toString() {
        return field + " " + operator + " " + value;
    }
}

