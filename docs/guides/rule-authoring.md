# Rule Authoring Guide

## Rule Schema

Rules are defined in JSON.

```json
{
  "rule_code": "UNIQUE_RULE_ID",           // Required
  "priority": 100,                         // Optional (default 0)
  "description": "Human-readable desc",    // Optional
  "enabled": true,                         // Optional (default true)
  "conditions": [                          // Required (AND logic)
    {
      "field": "field_name",
      "operator": "EQUAL_TO",
      "value": "expected_value"
    }
  ]
}
```

## Supported Operators

| Operator | Description | Value Type | Example |
|----------|-------------|------------|---------|
| `EQUAL_TO` | Exact match | Any | `{"operator": "EQUAL_TO", "value": "ACTIVE"}` |
| `NOT_EQUAL_TO` | Not equal | Any | `{"operator": "NOT_EQUAL_TO", "value": "SPAM"}` |
| `GREATER_THAN` | > | Number | `{"operator": "GREATER_THAN", "value": 1000}` |
| `LESS_THAN` | < | Number | `{"operator": "LESS_THAN", "value": 18}` |
| `BETWEEN` | Inclusive range | [min, max] | `{"operator": "BETWEEN", "value": [10, 100]}` |
| `IS_ANY_OF` | Match any (OR) | Array | `{"operator": "IS_ANY_OF", "value": ["US", "CA"]}` |
| `CONTAINS` | Substring | String | `{"operator": "CONTAINS", "value": "@example.com"}` |
| `REGEX` | Regex match | String | `{"operator": "REGEX", "value": "^\\+1-\\d{3}$"}` |

## Best Practices

1. **Use Descriptive Codes:** `FRAUD_HIGH_VELOCITY` instead of `RULE_1`.
2. **Leverage `IS_ANY_OF`:** Instead of creating multiple rules for different values of the same field, use `IS_ANY_OF`. This allows the engine to optimize and deduplicate.
   ```json
   // Efficient
   {"field": "country", "operator": "IS_ANY_OF", "value": ["US", "UK", "CA"]}
   ```
3. **Disable, Don't Delete:** Use `"enabled": false` to preserve history.
