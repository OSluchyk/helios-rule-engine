# Troubleshooting Guide

## Common Issues

### Rules Not Matching

1. **Check Field Normalization:**
   Field names are uppercased. Ensure your rule uses `customer_tier` if your event sends `customer_tier` (which becomes `CUSTOMER_TIER`).

2. **Verify Data Types:**
   JSON numbers are distinct from strings.
   - Rule: `{"value": 1000}` (Number)
   - Event: `"amount": "1000"` (String) -> **Mismatch**

3. **Enable Debug Logging:**
   ```java
   Logger.getLogger("com.helios.ruleengine").setLevel(Level.FINE);
   ```

### Performance Degradation

1. **Check Cache Hit Rate:**
   If hit rate < 70%, increase cache size or check for high-cardinality attributes in base conditions.

2. **Monitor GC:**
   Ensure ZGC is enabled. High pause times usually indicate memory pressure or wrong GC algorithm.

3. **Check Rule Expansion:**
   If `totalExpandedCombinations` is very high (>100k) but `deduplicationRatePercent` is low (<50%), review your rules for combinatorial explosions (e.g., many `IS_ANY_OF` on different fields in the same rule).
