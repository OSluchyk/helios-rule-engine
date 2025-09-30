package os.toolset.ruleengine.model;

import org.apache.commons.lang3.math.NumberUtils;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable atomic condition with operator, weight, and selectivity support.
 * The identity of a Predicate is defined by its field, operator, and value.
 * Weight and selectivity are considered metadata for optimization.
 */
public record Predicate(
        String field,
        Operator operator,
        Object value,
        Pattern pattern,
        float weight,       // Phase 4: Lower weight = evaluate first
        float selectivity   // Phase 4: Estimated probability of being true (0.0-1.0)
) {

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

    public Predicate {
        Objects.requireNonNull(field, "Field cannot be null");
        field = field.toUpperCase().replace('-', '_');
        Objects.requireNonNull(operator, "Operator cannot be null");
        Objects.requireNonNull(value, "Value cannot be null");

        if (selectivity == 0) {
            selectivity = estimateDefaultSelectivity(operator);
        }
        if (weight == 0) {
            weight = 1.0f - selectivity;
        }

        switch (operator) {
            case GREATER_THAN, LESS_THAN:
                if (!(value instanceof Number)) throw new IllegalArgumentException("Value for " + operator + " must be a Number.");
                break;
            case BETWEEN:
                if (!(value instanceof List<?> list) || list.size() != 2 || !(list.get(0) instanceof Number) || !(list.get(1) instanceof Number))
                    throw new IllegalArgumentException("Value for BETWEEN must be a List of two Numbers.");
                break;
            case CONTAINS, REGEX:
                if (!(value instanceof String)) throw new IllegalArgumentException("Value for " + operator + " must be a String.");
                if (operator == Operator.REGEX) {
                    try {
                        pattern = Pattern.compile((String) value);
                    } catch (Exception e) {
                        throw new IllegalArgumentException("Invalid regex pattern: " + value, e);
                    }
                }
                break;
            default: break;
        }
    }

    public Predicate(String field, Operator operator, Object value) {
        this(field, operator, value, null, 0, 0);
    }

    public Predicate(String field, Operator operator, Object value, float weight, float selectivity) {
        this(field, operator, value, null, weight, selectivity);
    }

    private static float estimateDefaultSelectivity(Operator operator) {
        return switch (operator) {
            case EQUAL_TO -> 0.1f;
            case GREATER_THAN, LESS_THAN -> 0.5f;
            case BETWEEN -> 0.3f;
            case CONTAINS -> 0.2f;
            case REGEX -> 0.15f;
            case IS_ANY_OF -> 0.2f;
        };
    }

    public float getEvaluationCost() {
        return switch (operator) {
            case EQUAL_TO -> 1.0f;
            case GREATER_THAN, LESS_THAN -> 1.1f;
            case BETWEEN -> 1.3f;
            case CONTAINS -> 3.0f;
            case REGEX -> 10.0f;
            case IS_ANY_OF -> 2.0f;
        };
    }

    public float getCombinedWeight() {
        return weight * getEvaluationCost();
    }

    public boolean evaluate(Object eventValue) {
        if (eventValue == null) return false;
        return switch (operator) {
            case EQUAL_TO -> isEqual(eventValue);
            case GREATER_THAN -> isGreaterThan(eventValue);
            case LESS_THAN -> isLessThan(eventValue);
            case BETWEEN -> isBetween(eventValue);
            case CONTAINS -> (eventValue instanceof String) && ((String) eventValue).contains((String) value);
            case REGEX -> (eventValue instanceof String) && pattern.matcher((String) eventValue).matches();
            default -> false;
        };
    }

    private boolean isEqual(Object eventValue) {
        if (value instanceof Number && eventValue instanceof Number) {
            return Double.compare(((Number) value).doubleValue(), ((Number) eventValue).doubleValue()) == 0;
        }
        return value.equals(eventValue);
    }

    private boolean isGreaterThan(Object eventValue) {
        if (eventValue instanceof Number) {
            return Double.compare(((Number) eventValue).doubleValue(), ((Number) value).doubleValue()) > 0;
        }
        return false;
    }

    private boolean isLessThan(Object eventValue) {
        if (eventValue instanceof Number) {
            return Double.compare(((Number) eventValue).doubleValue(), ((Number) value).doubleValue()) < 0;
        }
        return false;
    }

    private boolean isBetween(Object eventValue) {
        if (eventValue instanceof Number) {
            List<?> range = (List<?>) value;
            double val = ((Number) eventValue).doubleValue();
            double lower = ((Number) range.get(0)).doubleValue();
            double upper = ((Number) range.get(1)).doubleValue();
            return val >= lower && val <= upper;
        }
        return false;
    }

    // --- CRITICAL FIX ---
    // Override equals and hashCode to define predicate identity
    // based on logic, not on performance metadata.
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Predicate that = (Predicate) o;
        return Objects.equals(field, that.field) &&
                operator == that.operator &&
                Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(field, operator, value);
    }

    @Override
    public String toString() {
        return String.format("%s %s %s (w=%.2f, s=%.2f)", field, operator, value, weight(), selectivity());
    }
}