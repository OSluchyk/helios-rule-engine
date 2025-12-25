# Helios Rule Engine - UI Integration Quick Start Guide

**Version:** 1.0
**Date:** 2025-12-25

This guide shows how to use the new UI integration features that have been added to the Helios Rule Engine.

---

## 🚀 Quick Start

### 1. Access Rule Metadata

```java
// Compile rules
IRuleCompiler compiler = new RuleCompiler();
EngineModel model = compiler.compile(Path.of("rules.json"));

// Get all rule metadata
Collection<RuleMetadata> allRules = model.getAllRuleMetadata();
for (RuleMetadata rule : allRules) {
    System.out.println("Rule: " + rule.ruleCode());
    System.out.println("  Description: " + rule.description());
    System.out.println("  Priority: " + rule.priority());
    System.out.println("  Tags: " + rule.tags());
    System.out.println("  Combinations: " + rule.combinationIds().size());
}

// Get specific rule metadata
RuleMetadata rule = model.getRuleMetadata("RULE_12453");
if (rule != null) {
    System.out.println("Found rule: " + rule.description());
}
```

---

### 2. Explore Reverse Lookups

```java
// Find which combinations a rule expands to
String ruleCode = "RULE_12453";
Set<Integer> combinations = model.getCombinationIdsForRule(ruleCode);
System.out.println(ruleCode + " expands to " + combinations.size() + " combinations");

// Find which rules use a specific predicate
int predicateId = 42;
Set<String> rules = model.getRulesUsingPredicate(predicateId);
System.out.println("Predicate " + predicateId + " is used by " + rules.size() + " rules");
System.out.println("  Rules: " + rules);
```

---

### 3. Analyze Rule Conflicts

```java
// Analyze for conflicts
RuleConflictAnalyzer analyzer = new RuleConflictAnalyzer();
ConflictReport report = analyzer.analyzeConflicts(model);

if (report.hasConflicts()) {
    System.out.println("Found " + report.conflictCount() + " conflicts");

    // Show top conflicts by overlap
    for (RuleConflict conflict : report.sortedByOverlap()) {
        System.out.println(conflict.describe());
        System.out.println("  Severity: " + conflict.severity());
        if (conflict.hasPriorityConflict()) {
            System.out.println("  ⚠️ Priority conflict detected!");
        }
    }

    // Show only conflicts with different priorities
    List<RuleConflict> priorityConflicts = report.differentPriorityConflicts();
    System.out.println("\nPriority conflicts: " + priorityConflicts.size());
}
```

---

### 4. Use Evaluation Tracing (Debugging)

```java
// Enable tracing for debugging
IRuleEvaluator evaluator = new RuleEvaluator(model);

Event event = new Event("evt_123", "USER_ACTION", Map.of(
    "customer_segment", "premium",
    "lifetime_value", 15000.0,
    "region", "US"
));

// Evaluate with trace
EvaluationResult result = evaluator.evaluateWithTrace(event);

// Analyze trace
EvaluationTrace trace = result.trace();
System.out.println("Total duration: " + trace.totalDurationMillis() + " ms");
System.out.println("  Dict encoding: " + trace.dictEncodingNanos() / 1000 + " μs");
System.out.println("  Base condition: " + trace.baseConditionNanos() / 1000 + " μs");
System.out.println("  Predicate eval: " + trace.predicateEvalNanos() / 1000 + " μs");

// Examine predicate outcomes
for (var outcome : trace.predicateOutcomes()) {
    if (!outcome.matched()) {
        System.out.println("❌ " + outcome.describe());
    }
}

// Examine rule details
for (var detail : trace.ruleDetails()) {
    System.out.println(detail.describe());
}
```

---

### 5. Explain Specific Rule

```java
// Explain why a rule matched/didn't match
ExplanationResult explanation = evaluator.explainRule(event, "RULE_12453");

System.out.println("Rule: " + explanation.ruleCode());
System.out.println("Matched: " + explanation.matched());
System.out.println("Summary: " + explanation.summary());

// Show condition-by-condition analysis
System.out.println("\nConditions:");
for (var cond : explanation.conditionExplanations()) {
    System.out.println("  " + cond.describe());
}

// Print full explanation
System.out.println("\n" + explanation.toDetailedString());
```

---

### 6. Batch Evaluation

```java
// Evaluate multiple events
List<Event> events = List.of(
    new Event("evt_1", "ACTION", Map.of("value", 100)),
    new Event("evt_2", "ACTION", Map.of("value", 200)),
    new Event("evt_3", "ACTION", Map.of("value", 300))
);

List<MatchResult> results = evaluator.evaluateBatch(events);

// Analyze results
long totalMatches = results.stream()
    .mapToLong(r -> r.matchedRules().size())
    .sum();
System.out.println("Total matches: " + totalMatches);

double avgLatency = results.stream()
    .mapToLong(MatchResult::evaluationTimeNanos)
    .average()
    .orElse(0.0);
System.out.println("Avg latency: " + avgLatency / 1000 + " μs");
```

---

### 7. Create Rule Metadata

```java
// Create metadata when compiling
RuleDefinition def = // ... loaded from JSON

RuleMetadata metadata = RuleMetadata.fromDefinition(def)
    .withVersionUpdate("user@company.com", 2)
    .withCompilationMetadata(
        Set.of(1, 2, 3),  // combination IDs
        25,               // estimated selectivity (%)
        true,             // is vectorizable
        "OK"              // compilation status
    );

// Register with engine model builder
EngineModel.Builder builder = new EngineModel.Builder();
builder.addRuleMetadata(metadata);
```

---

### 8. Track Compilation Progress (Future)

```java
// Set up compilation listener
CompilationListener listener = new CompilationListener() {
    @Override
    public void onStageStart(String stageName, int stageNumber, int totalStages) {
        System.out.printf("[%d/%d] Starting %s...%n",
            stageNumber, totalStages, stageName);
    }

    @Override
    public void onStageComplete(String stageName, StageResult result) {
        System.out.printf("  ✓ %s completed in %d ms%n",
            stageName, result.durationMillis());
    }

    @Override
    public void onError(String stageName, Exception error) {
        System.err.printf("  ✗ %s failed: %s%n",
            stageName, error.getMessage());
    }
};

// Use listener during compilation (requires implementation)
IRuleCompiler compiler = new RuleCompiler();
compiler.setCompilationListener(listener);
EngineModel model = compiler.compile(rulesPath);
```

---

## 📊 Performance Notes

### Hot Path (Production)
```java
// Standard evaluation - ZERO OVERHEAD
MatchResult result = evaluator.evaluate(event);  // < 300μs typical
```

### Debugging (Development)
```java
// With tracing - ~10% overhead (acceptable for debugging)
EvaluationResult result = evaluator.evaluateWithTrace(event);  // ~330μs typical
```

### Conflict Analysis (On-Demand)
```java
// O(N²) complexity - run periodically, not on every compilation
RuleConflictAnalyzer analyzer = new RuleConflictAnalyzer();
ConflictReport report = analyzer.analyzeConflicts(model);  // ~500ms for 1000 rules
```

---

## 🎯 Use Cases

### Use Case 1: Rule Dashboard UI
```java
// List all rules with metadata
Collection<RuleMetadata> rules = model.getAllRuleMetadata();

// For each rule, show:
// - Basic info (code, description, priority)
// - Categorization (tags, labels)
// - Compilation status
// - Number of combinations (deduplication insight)

// Filter by tag
List<RuleMetadata> upsellRules = rules.stream()
    .filter(r -> r.tags().contains("upsell"))
    .toList();
```

### Use Case 2: Debugging "Why didn't this match?"
```java
// User reports: "Event X should have matched Rule Y but didn't"
Event problematicEvent = // ... from logs
String ruleCode = "SUSPECTED_RULE";

ExplanationResult explanation = evaluator.explainRule(problematicEvent, ruleCode);

// Show user which conditions failed
for (var cond : explanation.conditionExplanations()) {
    if (!cond.passed()) {
        System.out.println("Failed: " + cond.fieldName());
        System.out.println("  Expected: " + cond.expectedValue());
        System.out.println("  Actual: " + cond.actualValue());
        System.out.println("  Reason: " + cond.reason());
    }
}
```

### Use Case 3: Predicate Usage Analysis
```java
// Find most-used predicates (for optimization)
Map<Integer, Integer> predicateUsage = new HashMap<>();
for (int i = 0; i < model.getUniquePredicates().length; i++) {
    Set<String> rules = model.getRulesUsingPredicate(i);
    predicateUsage.put(i, rules.size());
}

// Sort by usage
predicateUsage.entrySet().stream()
    .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
    .limit(10)
    .forEach(entry -> {
        Predicate p = model.getPredicate(entry.getKey());
        String fieldName = model.getFieldDictionary().decode(p.fieldId());
        System.out.printf("Predicate %s %s: used by %d rules%n",
            fieldName, p.operator(), entry.getValue());
    });
```

### Use Case 4: Deduplication Analysis
```java
// Show deduplication effectiveness
EngineStats stats = model.getStats();
Map<String, Object> metadata = stats.metadata();

int logicalRules = (int) metadata.get("logicalRules");
int uniqueCombinations = stats.uniqueCombinations();
double dedupRate = (double) metadata.get("deduplicationRatePercent");

System.out.printf("Deduplication: %d rules → %d combinations (%.1f%% reduction)%n",
    logicalRules, uniqueCombinations, dedupRate);

// Find rules with most deduplication
for (RuleMetadata rule : model.getAllRuleMetadata()) {
    int combinations = rule.combinationIds().size();
    if (combinations > 5) {  // Many combinations
        System.out.printf("Rule %s expands to %d combinations%n",
            rule.ruleCode(), combinations);
    }
}
```

---

## 🔍 Inspection Utilities

### Decode Dictionary Values
```java
// Decode a predicate's value
Predicate p = model.getPredicate(predicateId);
Object value = p.value();

if (value instanceof Integer) {
    String decoded = model.getValueDictionary().decode((Integer) value);
    System.out.println("Value: " + decoded + " (ID: " + value + ")");
} else {
    System.out.println("Value: " + value);
}

// Decode field name
String fieldName = model.getFieldDictionary().decode(p.fieldId());
System.out.println("Field: " + fieldName);
```

### Analyze Timing Breakdown
```java
EvaluationTrace trace = // ... from evaluateWithTrace()
var breakdown = trace.getTimingBreakdown();

System.out.printf("Timing breakdown:%n");
System.out.printf("  Dict encoding: %.1f%%%n", breakdown.dictEncodingPercent());
System.out.printf("  Base condition: %.1f%%%n", breakdown.baseConditionPercent());
System.out.printf("  Predicate eval: %.1f%%%n", breakdown.predicateEvalPercent());
System.out.printf("  Counter update: %.1f%%%n", breakdown.counterUpdatePercent());
System.out.printf("  Match detection: %.1f%%%n", breakdown.matchDetectionPercent());
```

---

## 🎓 Best Practices

### 1. Use Tracing Only for Debugging
```java
// ❌ DON'T: Use tracing in production
EvaluationResult result = evaluator.evaluateWithTrace(event);  // 10% overhead

// ✅ DO: Use standard evaluation in production
MatchResult result = evaluator.evaluate(event);  // Zero overhead
```

### 2. Run Conflict Analysis Off-Path
```java
// ❌ DON'T: Run conflict analysis during compilation
EngineModel model = compiler.compile(rulesPath);
ConflictReport report = analyzer.analyzeConflicts(model);  // O(N²)!

// ✅ DO: Run conflict analysis on-demand or periodically
EngineModel model = compiler.compile(rulesPath);
// ... later, when UI requests it:
ConflictReport report = analyzer.analyzeConflicts(model);
```

### 3. Cache Metadata Lookups
```java
// ❌ DON'T: Re-fetch metadata repeatedly
for (int i = 0; i < 1000; i++) {
    RuleMetadata metadata = model.getRuleMetadata(ruleCode);  // Repeated lookup
}

// ✅ DO: Fetch once and cache
RuleMetadata metadata = model.getRuleMetadata(ruleCode);
// Use metadata multiple times
```

---

## 📚 Additional Resources

- **Full Specification:** `UI_INTEGRATION_SPECIFICATION.md`
- **Implementation Summary:** `UI_INTEGRATION_IMPLEMENTATION_SUMMARY.md`
- **Javadoc:** See inline documentation in each class

---

**Happy Coding!** 🎉
