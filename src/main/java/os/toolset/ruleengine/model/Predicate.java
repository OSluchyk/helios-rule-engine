package os.toolset.ruleengine.model;

import org.apache.commons.lang3.math.NumberUtils;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Immutable atomic condition using dictionary-encoded integer IDs for fields and values.
 * Weight and selectivity are metadata for optimization.
 */
public record Predicate(
        int fieldId,
        Operator operator,
        Object value, // Can be int ID, List<Integer> for BETWEEN, or original value for REGEX/CONTAINS
        Pattern pattern,
        float weight,
        float selectivity
) {

    public enum Operator {
        EQUAL_TO, IS_ANY_OF,
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

    public Predicate {
        Objects.requireNonNull(operator, "Operator cannot be null");
        Objects.requireNonNull(value, "Value cannot be null");
    }

    // Simplified constructor for use in tests or where metadata is not needed
    public Predicate(int fieldId, Operator operator, Object value) {
        this(fieldId, operator, value, null, 0, 0);
    }

    public boolean evaluate(Object eventValue) {
        if (eventValue == null) return false;
        return switch (operator) {
            case EQUAL_TO -> value.equals(eventValue);
            case GREATER_THAN -> isGreaterThan(eventValue);
            case LESS_THAN -> isLessThan(eventValue);
            case BETWEEN -> isBetween(eventValue);
            case CONTAINS -> (eventValue instanceof String) && ((String) eventValue).contains((String) value);
            case REGEX -> (eventValue instanceof String) && pattern.matcher((String) eventValue).matches();
            default -> false; // IS_ANY_OF is expanded by the compiler
        };
    }

    private boolean isGreaterThan(Object eventValue) {
        if (eventValue instanceof Number && value instanceof Number) {
            return Double.compare(((Number) eventValue).doubleValue(), ((Number) value).doubleValue()) > 0;
        }
        return false;
    }

    private boolean isLessThan(Object eventValue) {
        if (eventValue instanceof Number && value instanceof Number) {
            return Double.compare(((Number) eventValue).doubleValue(), ((Number) value).doubleValue()) < 0;
        }
        return false;
    }

    private boolean isBetween(Object eventValue) {
        if (eventValue instanceof Number && value instanceof List<?> range) {
            double val = ((Number) eventValue).doubleValue();
            double lower = ((Number) range.get(0)).doubleValue();
            double upper = ((Number) range.get(1)).doubleValue();
            return val >= lower && val <= upper;
        }
        return false;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Predicate that = (Predicate) o;
        return fieldId == that.fieldId &&
                operator == that.operator &&
                Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fieldId, operator, value);
    }
}