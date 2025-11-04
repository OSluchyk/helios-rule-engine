package com.helios.ruleengine.model;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable atomic condition using dictionary-encoded integer IDs for fields and values.
 * Weight and selectivity are metadata for optimization.
 */
public record Predicate(
        int fieldId,
        Operator operator,
        Object value,
        Pattern pattern,
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

        public static Operator fromString(String text) {
            if (text == null) return null;
            try {
                return Operator.valueOf(text.toUpperCase());
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

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

    public Predicate {
        Objects.requireNonNull(operator, "Operator cannot be null");
        if (operator != Operator.IS_NULL && operator != Operator.IS_NOT_NULL) {
            Objects.requireNonNull(value, "Value cannot be null for operator " + operator);
        }
    }

    public Predicate(int fieldId, Operator operator, Object value) {
        this(fieldId, operator, value, null, 0, 0);
    }

    public boolean evaluate(Object eventValue) {
        if (eventValue == null) return false;
        // Non-numeric and non-vectorizable operations
        return switch (operator) {
            case EQUAL_TO -> value.equals(eventValue);
            case NOT_EQUAL_TO -> !value.equals(eventValue);
            case CONTAINS -> (eventValue instanceof String) && ((String) eventValue).contains((String) value);
            case REGEX -> (eventValue instanceof String) && pattern.matcher((String) eventValue).matches();
            // Numeric operations are now handled by dedicated methods for vectorization
            case GREATER_THAN, LESS_THAN, BETWEEN -> evaluateNumeric(eventValue);
            default -> false;
        };
    }

    // Dedicated method for numeric evaluation
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Predicate that = (Predicate) o;
        return fieldId == that.fieldId && operator == that.operator && Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fieldId, operator, value);
    }
}