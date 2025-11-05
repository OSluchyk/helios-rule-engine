package com.helios.ruleengine.model;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Represents an immutable, atomic condition within the compiled engine.
 * It uses dictionary-encoded integer IDs for fields and (where applicable) values.
 *
 * Weight and selectivity are metadata calculated at compile time
 * to guide runtime evaluation optimizations.
 */
public record Predicate(
        int fieldId,
        Operator operator,
        Object value,
        Pattern pattern, // Pre-compiled regex pattern, null for other operators
        float weight,
        float selectivity
) {

    public enum Operator {
        EQUAL_TO, NOT_EQUAL_TO,
        IS_ANY_OF, IS_NONE_OF,
        GREATER_THAN, LESS_THAN, BETWEEN, CONTAINS, REGEX,
        GREATER_THAN_OR_EQUAL, LESS_THAN_OR_EQUAL,
        IS_NULL, IS_NOT_NULL,
        STARTS_WITH, ENDS_WITH;

        /**
         * Safely converts a string to an Operator enum.
         * @param text The operator string (e.g., "EQUAL_TO").
         * @return The corresponding Operator, or null if not found.
         */
        public static Operator fromString(String text) {
            if (text == null) return null;
            try {
                return Operator.valueOf(text.toUpperCase());
            } catch (IllegalArgumentException e) {
                return null; // Unknown operator
            }
        }

        /**
         * Checks if this operator performs a numeric comparison.
         */
        public boolean isNumeric() {
            return this == GREATER_THAN
                    || this == GREATER_THAN_OR_EQUAL
                    || this == LESS_THAN_OR_EQUAL
                    || this == LESS_THAN
                    || this == BETWEEN;
        }

        @JsonValue
        public String getValue() {
            return name();
        }
    }

    /**
     * Constructor that validates predicate invariants.
     */
    public Predicate {
        Objects.requireNonNull(operator, "Operator cannot be null");
        // Value can be null *only* for IS_NULL and IS_NOT_NULL operators
        if (operator != Operator.IS_NULL && operator != Operator.IS_NOT_NULL) {
            Objects.requireNonNull(value, "Value cannot be null for operator " + operator);
        }
    }

    /**
     * Simplified constructor for testing.
     */
    public Predicate(int fieldId, Operator operator, Object value) {
        this(fieldId, operator, value, null, 0, 0);
    }

    /**
     * Placeholder evaluation logic (runtime logic is in RuleEvaluator).
     */
    public boolean evaluate(Object eventValue) {
        if (eventValue == null) {
            // Handle null checks
            return switch (operator) {
                case IS_NULL -> true;
                case IS_NOT_NULL -> false;
                default -> false;
            };
        }

        // Non-null evaluation
        return switch (operator) {
            case IS_NULL -> false;
            case IS_NOT_NULL -> true;
            case EQUAL_TO -> value.equals(eventValue);
            case NOT_EQUAL_TO -> !value.equals(eventValue);
            case CONTAINS -> (eventValue instanceof String) && ((String) eventValue).contains((String) value);
            case REGEX -> (eventValue instanceof String) && pattern.matcher((String) eventValue).matches();
            case GREATER_THAN, LESS_THAN, BETWEEN -> evaluateNumeric(eventValue);
            // Other operators (IS_ANY_OF, etc.) are handled by the evaluator logic
            default -> false;
        };
    }

    /**
     * Placeholder numeric evaluation.
     */
    private boolean evaluateNumeric(Object eventValue) {
        if (!(eventValue instanceof Number)) return false;
        double eventDouble = ((Number) eventValue).doubleValue();

        return switch (operator) {
            case GREATER_THAN -> eventDouble > ((Number) value).doubleValue();
            case LESS_THAN -> eventDouble < ((Number) value).doubleValue();
            case BETWEEN -> {
                List<?> range = (List<?>) value;
                double lower = ((Number) range.get(0)).doubleValue();
                double upper = ((Number) range.get(1)).doubleValue();
                yield eventDouble >= lower && eventDouble <= upper;
            }
            default -> false;
        };
    }

    /**
     * Overridden equals for logical predicate equality.
     * Note: This does *not* include weight or selectivity,
     * as they are metadata, not part of the logical identity.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Predicate that = (Predicate) o;
        return fieldId == that.fieldId &&
                operator == that.operator &&
                Objects.equals(value, that.value) &&
                Objects.equals(patternToString(), that.patternToString()); // Compare pattern strings
    }

    /**
     * Overridden hashCode for logical predicate hashing.
     */
    @Override
    public int hashCode() {
        return Objects.hash(fieldId, operator, value, patternToString());
    }

    private String patternToString() {
        return pattern != null ? pattern.pattern() : null;
    }
}