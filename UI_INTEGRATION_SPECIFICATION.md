# Helios Rule Engine - UI Integration Specification

**Version:** 1.0
**Date:** 2025-12-25
**Status:** DESIGN PHASE - NO CODE MODIFICATIONS

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Current Architecture Analysis](#current-architecture-analysis)
3. [Required Core Engine Modifications](#required-core-engine-modifications)
4. [API Contract Specifications](#api-contract-specifications)
5. [Mock UI Requirements](#mock-ui-requirements)
6. [Implementation Roadmap](#implementation-roadmap)
7. [Performance Impact Assessment](#performance-impact-assessment)

---

## 1. Executive Summary

### Objective

Design and specify a comprehensive UI integration layer for the Helios Rule Engine that exposes:

- **Logical Rule Management**: CRUD operations, validation, versioning
- **Compilation Pipeline Visibility**: Stage-by-stage insights, optimization metrics
- **Evaluation Debugging**: Step-by-step execution tracing, "why did this match?" explainer
- **Performance Monitoring**: Real-time metrics, latency histograms, cache analytics
- **A/B Testing & Rollouts**: Gradual deployment, variant comparison

### Design Principles

1. **Zero Performance Impact**: UI features must not affect hot path evaluation (<1% overhead acceptable)
2. **Read-Heavy Architecture**: Most UI operations are read-only snapshots
3. **Preserve Existing Behavior**: All changes are additive, no breaking changes
4. **Observable by Default**: Compilation and evaluation stages expose detailed telemetry

### Key Constraints

- ❌ **NO modifications to existing code** during this design phase
- ✅ **Document ALL changes** as TODO items with file paths and line numbers
- ⚠️ **Maintain backward compatibility** with existing APIs
- 📝 **Performance benchmarks required** before and after implementation

---

## 2. Current Architecture Analysis

### 2.1 Strengths (Already Available)

✅ **Well-Structured Compilation Pipeline**
- Location: `helios-compiler/src/main/java/com/helios/ruleengine/compiler/RuleCompiler.java`
- Current: 7 distinct stages (parsing → dictionary encoding → deduplication → inverted index)
- UI-Ready: Each stage logs to OpenTelemetry spans with attributes

✅ **Rich Metadata Capture**
- `EngineStats`: Compilation time, deduplication rate, unique combinations
- `EvaluatorMetrics`: Latency percentiles (P50, P95, P99), cache hit rates
- `MatchResult`: Per-evaluation metrics (predicates evaluated, matched rules)

✅ **Thread-Safe, Immutable Data Structures**
- `EngineModel`: Fully immutable after compilation
- `RuleEvaluator`: Thread-safe via ScopedValue and ThreadLocal pooling
- Concurrent evaluation support without locks

✅ **Hot-Reload Support**
- `EngineModelManager`: Detects file changes, compiles new model, atomic swap
- Zero-downtime rule updates

✅ **Dictionary Encoding for Efficient Lookups**
- `Dictionary` class: Bidirectional String ↔ Integer mapping
- UI can reverse-lookup field names and values by ID

### 2.2 Gaps (Require New Features)

❌ **No Per-Rule Metadata Preservation**
- **Issue**: After compilation, only `RuleDefinition[]` array is stored (legacy)
- **Impact**: Cannot retrieve original rule JSON with full description/metadata
- **Needed**: Rich `RuleMetadata` DTO with creation timestamp, author, tags, version

❌ **No Logical→Physical Rule Mapping**
- **Issue**: UI cannot determine which logical rules map to which combinations
- **Impact**: Cannot show "Rules #1, #5, #12 share conditions X, Y, Z"
- **Needed**: `Map<String, Set<Integer>> ruleCodeToCombinationIds`

❌ **No Predicate→Rule Reverse Lookup**
- **Issue**: Cannot answer "Which rules use predicate P?"
- **Impact**: Cannot show predicate usage heatmap or dependency graph
- **Needed**: `Map<Integer, Set<String>> predicateIdToRuleCodes`

❌ **No Evaluation Trace Capture**
- **Issue**: `RuleEvaluator` does not store step-by-step execution details
- **Impact**: Cannot debug "Why did rule X not match?" or visualize DAG
- **Needed**: Optional `EvaluationTrace` object with predicate outcomes, timing

❌ **No Base Condition Set Visibility**
- **Issue**: `BaseConditionEvaluator` caches base conditions, but no introspection API
- **Impact**: UI cannot show "What are the unique base condition sets?"
- **Needed**: Expose base condition sets and their associated rules

❌ **No Compilation Stage Hooks**
- **Issue**: Compiler runs all stages atomically, no intermediate access
- **Impact**: Cannot show progressive compilation progress or debug optimizer
- **Needed**: Callback/listener for each compilation stage completion

❌ **No Rule Conflict Detection**
- **Issue**: Overlapping rules not detected or reported
- **Impact**: Users unknowingly create ambiguous rules
- **Needed**: Post-compilation conflict analyzer with similarity scoring

❌ **No Test Case Management**
- **Issue**: No structured way to store/replay test events
- **Impact**: Manual testing only, no regression suite
- **Needed**: Test case persistence, batch evaluation API

---

## 3. Required Core Engine Modifications

### 3.1 helios-api Module

**Purpose:** Define new DTOs and interfaces for UI integration

#### 3.1.1 New DTOs for Rich Metadata

**File:** `/helios-api/src/main/java/com/helios/ruleengine/api/model/RuleMetadata.java`

```java
/**
 * Enhanced metadata for a logical rule, beyond RuleDefinition.
 * Stores authoring, versioning, and lifecycle information.
 */
public record RuleMetadata(
    String ruleCode,              // Unique identifier
    String description,           // Human-readable description
    List<Condition> conditions,   // Original conditions (from RuleDefinition)
    Integer priority,             // Priority (default: 0)
    Boolean enabled,              // Active/inactive flag

    // NEW FIELDS (not in RuleDefinition):
    String createdBy,             // User/system that created the rule
    Instant createdAt,            // Timestamp of creation
    String lastModifiedBy,        // User/system that last modified
    Instant lastModifiedAt,       // Timestamp of last modification
    Integer version,              // Monotonic version number (1, 2, 3...)
    Set<String> tags,             // Custom tags ("fraud", "high-value", etc.)
    Map<String, String> labels,   // Key-value labels (team=risk, region=US)

    // Compilation-derived fields:
    Set<Integer> combinationIds,  // Physical combinations this rule maps to
    Integer estimatedSelectivity, // % of events expected to match (0-100)
    Boolean isVectorizable,       // True if all conditions are vectorizable
    String compilationStatus      // "OK", "WARNING", "ERROR"
) {
    // Default values for optional fields
    public RuleMetadata {
        if (enabled == null) enabled = true;
        if (priority == null) priority = 0;
        if (tags == null) tags = Set.of();
        if (labels == null) labels = Map.of();
        if (createdAt == null) createdAt = Instant.now();
        if (lastModifiedAt == null) lastModifiedAt = createdAt;
        if (version == null) version = 1;
        if (compilationStatus == null) compilationStatus = "OK";
    }
}
```

**TODO: Create this file** ✅ NEW FILE

---

#### 3.1.2 New DTO for Evaluation Tracing

**File:** `/helios-api/src/main/java/com/helios/ruleengine/api/model/EvaluationTrace.java`

```java
/**
 * Detailed execution trace for a single event evaluation.
 * Captures step-by-step predicate outcomes and timing.
 */
public record EvaluationTrace(
    String eventId,                      // Event being evaluated
    long totalDurationNanos,              // Total evaluation time

    // Stage timings
    long dictEncodingNanos,               // Time to encode event attributes
    long baseConditionNanos,              // Time for base condition evaluation
    long predicateEvalNanos,              // Time for predicate evaluation
    long counterUpdateNanos,              // Time to update counters
    long matchDetectionNanos,             // Time to detect matches

    // Predicate-level details
    List<PredicateOutcome> predicateOutcomes, // All evaluated predicates

    // Rule-level details
    List<RuleEvaluationDetail> ruleDetails,   // Touched rules with counter values

    // Cache effectiveness
    boolean baseConditionCacheHit,        // True if base conditions were cached
    int eligibleRulesCount,               // # of rules after base filtering

    // Final results
    List<String> matchedRuleCodes         // Rules that matched
) {
    /**
     * Outcome of a single predicate evaluation.
     */
    public record PredicateOutcome(
        int predicateId,
        String fieldName,            // Decoded field name (e.g., "CUSTOMER_SEGMENT")
        String operator,             // "EQUAL_TO", "GREATER_THAN", etc.
        Object expectedValue,        // Expected value (decoded if string)
        Object actualValue,          // Actual value from event (decoded if string)
        boolean matched,             // True if predicate passed
        long evaluationNanos         // Time to evaluate this predicate
    ) {}

    /**
     * Details for a single rule combination.
     */
    public record RuleEvaluationDetail(
        int combinationId,
        String ruleCode,             // Primary rule code
        int priority,
        int predicatesMatched,       // # of predicates that matched
        int predicatesRequired,      // Total # of predicates needed
        boolean finalMatch,          // True if rule matched
        List<String> failedPredicates // Field names of failed predicates
    ) {}
}
```

**TODO: Create this file** ✅ NEW FILE

---

#### 3.1.3 Extended Compiler Interface

**File:** `/helios-api/src/main/java/com/helios/ruleengine/api/IRuleCompiler.java`

**TODO: Add new methods** 📝 MODIFY EXISTING

```java
public interface IRuleCompiler {
    // Existing method
    EngineModel compile(Path rulesPath) throws Exception;

    // NEW: Compile with compilation listener for stage-by-stage feedback
    EngineModel compile(Path rulesPath, CompilationListener listener) throws Exception;

    // NEW: Validate rules without full compilation (fast validation)
    ValidationReport validateRules(Path rulesPath) throws Exception;

    // Existing method
    void setTracer(Tracer tracer);
}

/**
 * Callback interface for compilation stage events.
 * Allows UI to show progressive compilation status.
 */
public interface CompilationListener {
    void onStageStart(String stageName, int stageNumber, int totalStages);
    void onStageComplete(String stageName, StageResult result);
    void onError(String stageName, Exception error);
}

/**
 * Result of a single compilation stage.
 */
public record StageResult(
    String stageName,
    long durationNanos,
    Map<String, Object> metrics  // Stage-specific metrics
) {}

/**
 * Validation report without full compilation.
 */
public record ValidationReport(
    boolean isValid,
    List<RuleValidationError> errors,
    List<RuleValidationWarning> warnings
) {
    public record RuleValidationError(
        String ruleCode,
        String message,
        String field,            // Field that caused error (nullable)
        int conditionIndex       // Index of condition that failed (or -1)
    ) {}

    public record RuleValidationWarning(
        String ruleCode,
        String message,
        String severity          // "LOW", "MEDIUM", "HIGH"
    ) {}
}
```

**Location:** Modify existing file at `/helios-api/src/main/java/com/helios/ruleengine/api/IRuleCompiler.java`
**Lines to modify:** Add after line 5 (after existing `compile` method)

---

#### 3.1.4 Extended Evaluator Interface

**File:** `/helios-api/src/main/java/com/helios/ruleengine/api/IRuleEvaluator.java`

**TODO: Add new methods** 📝 MODIFY EXISTING

```java
public interface IRuleEvaluator {
    // Existing method
    MatchResult evaluate(Event event);

    // NEW: Evaluate with detailed tracing (for debugging)
    EvaluationResult evaluateWithTrace(Event event);

    // NEW: Explain why a specific rule did/didn't match
    ExplanationResult explainRule(Event event, String ruleCode);

    // NEW: Batch evaluation (for test suite execution)
    List<MatchResult> evaluateBatch(List<Event> events);
}

/**
 * Enhanced evaluation result with optional trace.
 */
public record EvaluationResult(
    MatchResult matchResult,      // Standard match result
    EvaluationTrace trace         // Detailed trace (null if tracing disabled)
) {}

/**
 * Explanation for why a rule matched or didn't match.
 */
public record ExplanationResult(
    String ruleCode,
    boolean matched,
    String summary,               // Human-readable summary
    List<ConditionExplanation> conditionExplanations
) {
    public record ConditionExplanation(
        String fieldName,
        String operator,
        Object expectedValue,
        Object actualValue,
        boolean passed,
        String reason             // "Value mismatch", "Field missing", etc.
    ) {}
}
```

**Location:** Modify existing file at `/helios-api/src/main/java/com/helios/ruleengine/api/IRuleEvaluator.java`
**Lines to modify:** Add after line 4 (after existing `evaluate` method)

---

### 3.2 helios-compiler Module

#### 3.2.1 Enhance RuleCompiler with Listeners

**File:** `/helios-compiler/src/main/java/com/helios/ruleengine/compiler/RuleCompiler.java`

**TODO: Add listener support** 📝 MODIFY EXISTING

```java
public class RuleCompiler implements IRuleCompiler {
    private Tracer tracer;
    private CompilationListener listener;  // NEW FIELD

    // NEW: Setter for listener
    public void setCompilationListener(CompilationListener listener) {
        this.listener = listener;
    }

    // MODIFY existing compile() method to emit events
    public EngineModel compile(Path rulesPath, SelectionStrategy strategy)
            throws IOException, CompilationException {

        notifyStageStart("PARSING", 1, 7);
        List<RuleDefinition> definitions = loadRules(rulesPath);
        notifyStageComplete("PARSING", Map.of("ruleCount", definitions.size()));

        notifyStageStart("VALIDATION", 2, 7);
        List<RuleDefinition> validRules = validateAndCanonize(definitions);
        notifyStageComplete("VALIDATION", Map.of("validRuleCount", validRules.size()));

        // ... repeat for all 7 stages ...
    }

    // NEW: Helper to notify listener
    private void notifyStageStart(String stage, int num, int total) {
        if (listener != null) {
            listener.onStageStart(stage, num, total);
        }
    }

    private void notifyStageComplete(String stage, Map<String, Object> metrics) {
        if (listener != null) {
            long duration = 0; // Track per-stage timing
            listener.onStageComplete(stage, new StageResult(stage, duration, metrics));
        }
    }
}
```

**Location:** `/helios-compiler/src/main/java/com/helios/ruleengine/compiler/RuleCompiler.java`
**Lines to modify:**
- Add field after line 34 (after `tracer` field)
- Add methods after line 140 (after `setTracer` method)
- Modify `compile` method at line 66 to add notifications

---

#### 3.2.2 Create Rule Conflict Analyzer

**File:** `/helios-compiler/src/main/java/com/helios/ruleengine/compiler/analysis/RuleConflictAnalyzer.java`

```java
/**
 * Analyzes compiled rules for conflicts, overlaps, and redundancies.
 */
public class RuleConflictAnalyzer {

    /**
     * Detects rules with overlapping conditions that may cause ambiguity.
     */
    public ConflictReport analyzeConflicts(EngineModel model) {
        List<RuleConflict> conflicts = new ArrayList<>();

        // Compare all combinations pairwise for condition overlap
        int numCombinations = model.getNumRules();
        for (int i = 0; i < numCombinations; i++) {
            for (int j = i + 1; j < numCombinations; j++) {
                RuleConflict conflict = checkConflict(model, i, j);
                if (conflict != null) {
                    conflicts.add(conflict);
                }
            }
        }

        return new ConflictReport(conflicts);
    }

    private RuleConflict checkConflict(EngineModel model, int combId1, int combId2) {
        IntList predicates1 = model.getCombinationPredicateIds(combId1);
        IntList predicates2 = model.getCombinationPredicateIds(combId2);

        // Calculate Jaccard similarity: |A ∩ B| / |A ∪ B|
        IntSet set1 = new IntOpenHashSet(predicates1);
        IntSet set2 = new IntOpenHashSet(predicates2);

        IntSet intersection = new IntOpenHashSet(set1);
        intersection.retainAll(set2);

        IntSet union = new IntOpenHashSet(set1);
        union.addAll(set2);

        double similarity = union.isEmpty() ? 0 :
            (double) intersection.size() / union.size();

        // Report conflicts with >50% overlap
        if (similarity > 0.5) {
            String rule1 = model.getCombinationRuleCode(combId1);
            String rule2 = model.getCombinationRuleCode(combId2);
            int priority1 = model.getCombinationPriority(combId1);
            int priority2 = model.getCombinationPriority(combId2);

            return new RuleConflict(
                rule1, rule2,
                similarity,
                priority1, priority2,
                intersection.size(),
                set1.size() - intersection.size(),
                set2.size() - intersection.size()
            );
        }

        return null;
    }

    public record ConflictReport(List<RuleConflict> conflicts) {}

    public record RuleConflict(
        String ruleCode1,
        String ruleCode2,
        double overlapPercentage,      // 0.0-1.0
        int priority1,
        int priority2,
        int sharedConditions,
        int uniqueToRule1,
        int uniqueToRule2
    ) {}
}
```

**TODO: Create this file** ✅ NEW FILE

---

### 3.3 helios-evaluator Module

#### 3.3.1 Add Tracing to RuleEvaluator

**File:** `/helios-evaluator/src/main/java/com/helios/ruleengine/runtime/evaluation/RuleEvaluator.java`

**TODO: Add trace capture** 📝 MODIFY EXISTING

```java
public final class RuleEvaluator implements IRuleEvaluator {

    private boolean tracingEnabled = false;  // NEW FIELD

    // NEW: Enable/disable tracing
    public void setTracingEnabled(boolean enabled) {
        this.tracingEnabled = enabled;
    }

    // NEW: Implement evaluateWithTrace
    @Override
    public EvaluationResult evaluateWithTrace(Event event) {
        boolean prevTracingState = tracingEnabled;
        tracingEnabled = true;
        try {
            MatchResult result = evaluate(event);
            EvaluationTrace trace = CONTEXT.get().buildTrace();
            return new EvaluationResult(result, trace);
        } finally {
            tracingEnabled = prevTracingState;
        }
    }

    // MODIFY existing doEvaluate to capture trace data
    private MatchResult doEvaluate(Event event) throws Exception {
        EvaluationContext ctx = CONTEXT.get();

        if (tracingEnabled) {
            ctx.initTraceCapture(model);  // NEW: Initialize trace structures
        }

        // ... existing evaluation logic ...

        // When evaluating predicates, capture outcomes:
        if (tracingEnabled) {
            ctx.recordPredicateOutcome(predicateId, fieldName, operator,
                                       expectedValue, actualValue, matched);
        }
    }
}
```

**Location:** `/helios-evaluator/src/main/java/com/helios/ruleengine/runtime/evaluation/RuleEvaluator.java`
**Lines to modify:**
- Add field after line 110 (after `eventEncoder` field)
- Add method after line 179 (after constructor)
- Modify `doEvaluate` at line 234 to add trace capture

---

#### 3.3.2 Extend EvaluationContext for Tracing

**File:** `/helios-evaluator/src/main/java/com/helios/ruleengine/runtime/context/EvaluationContext.java`

**TODO: Add trace capture fields** 📝 MODIFY EXISTING

```java
public class EvaluationContext {

    // NEW FIELDS for tracing
    private List<PredicateOutcome> predicateOutcomes;
    private Map<Integer, RuleEvaluationDetail> ruleDetails;
    private long dictEncodingNanos;
    private long baseConditionNanos;
    // ... other stage timings ...

    // NEW: Initialize trace structures
    public void initTraceCapture(EngineModel model) {
        this.predicateOutcomes = new ArrayList<>();
        this.ruleDetails = new HashMap<>();
    }

    // NEW: Record predicate outcome
    public void recordPredicateOutcome(int predicateId, String fieldName,
                                      String operator, Object expectedValue,
                                      Object actualValue, boolean matched) {
        predicateOutcomes.add(new PredicateOutcome(
            predicateId, fieldName, operator,
            expectedValue, actualValue, matched, 0L
        ));
    }

    // NEW: Build final trace
    public EvaluationTrace buildTrace() {
        return new EvaluationTrace(
            eventId, totalDurationNanos,
            dictEncodingNanos, baseConditionNanos,
            predicateEvalNanos, counterUpdateNanos,
            matchDetectionNanos,
            predicateOutcomes,
            new ArrayList<>(ruleDetails.values()),
            baseConditionCacheHit,
            eligibleRulesCount,
            matchedRuleCodes
        );
    }
}
```

**Location:** Find `EvaluationContext.java` (likely in `/helios-evaluator/src/main/java/com/helios/ruleengine/runtime/context/`)
**Lines to modify:** Add fields and methods at end of class

---

### 3.4 helios-core Module

#### 3.4.1 Enhance EngineModel with Reverse Lookups

**File:** `/helios-api/src/main/java/com/helios/ruleengine/runtime/model/EngineModel.java`

**TODO: Add reverse lookup maps** 📝 MODIFY EXISTING

```java
public final class EngineModel implements Serializable {

    // NEW FIELDS for UI introspection
    private final Map<String, Set<Integer>> ruleCodeToCombinationIds;
    private final Map<Integer, Set<String>> predicateIdToRuleCodes;
    private final Map<String, RuleMetadata> ruleMetadata;

    // NEW: Get all combinations for a logical rule
    public Set<Integer> getCombinationIdsForRule(String ruleCode) {
        return ruleCodeToCombinationIds.getOrDefault(ruleCode, Set.of());
    }

    // NEW: Get all rules using a predicate
    public Set<String> getRulesUsingPredicate(int predicateId) {
        return predicateIdToRuleCodes.getOrDefault(predicateId, Set.of());
    }

    // NEW: Get rich metadata for a rule
    public RuleMetadata getRuleMetadata(String ruleCode) {
        return ruleMetadata.get(ruleCode);
    }

    // NEW: Get all rule metadata
    public Collection<RuleMetadata> getAllRuleMetadata() {
        return ruleMetadata.values();
    }

    // MODIFY Builder to populate reverse maps
    public static class Builder {
        private final Map<String, Set<Integer>> ruleCodeToCombinationIds = new HashMap<>();
        private final Map<Integer, Set<String>> predicateIdToRuleCodes = new HashMap<>();
        private final Map<String, RuleMetadata> ruleMetadata = new HashMap<>();

        public void addLogicalRuleMapping(String ruleCode, Integer priority,
                                         String description, int combinationId) {
            // Existing logic...

            // NEW: Update reverse maps
            ruleCodeToCombinationIds
                .computeIfAbsent(ruleCode, k -> new HashSet<>())
                .add(combinationId);

            // Update predicate → rule mapping
            IntList predicateIds = idToCombinationMap.get(combinationId);
            if (predicateIds != null) {
                for (int predId : predicateIds) {
                    predicateIdToRuleCodes
                        .computeIfAbsent(predId, k -> new HashSet<>())
                        .add(ruleCode);
                }
            }
        }

        // NEW: Register rule metadata
        public void addRuleMetadata(RuleMetadata metadata) {
            ruleMetadata.put(metadata.ruleCode(), metadata);
        }
    }
}
```

**Location:** `/helios-api/src/main/java/com/helios/ruleengine/runtime/model/EngineModel.java`
**Lines to modify:**
- Add fields after line 83 (after `familyPriorities` field)
- Add methods after line 334 (after `getCombinationPrioritiesAll`)
- Modify `Builder.addLogicalRuleMapping` at line 439

---

#### 3.4.2 Expose Base Condition Sets

**File:** `/helios-evaluator/src/main/java/com/helios/ruleengine/runtime/evaluation/BaseConditionEvaluator.java`

**TODO: Add introspection methods** 📝 MODIFY EXISTING

```java
public class BaseConditionEvaluator {

    // NEW: Get all unique base condition sets
    public List<BaseConditionSet> getAllBaseConditionSets() {
        return baseConditionSets.values().stream()
            .map(this::toBaseConditionSetDTO)
            .collect(Collectors.toList());
    }

    // NEW: Get rules for a base condition set
    public Set<String> getRulesForBaseConditionSet(int baseConditionSetId) {
        // Implementation based on internal tracking
        return Set.of(); // Placeholder
    }

    private BaseConditionSet toBaseConditionSetDTO(/* internal representation */) {
        // Convert internal structure to DTO
        return new BaseConditionSet(
            baseConditionSetId,
            predicates,
            ruleCodesUsingThisSet,
            cacheHitCount
        );
    }

    public record BaseConditionSet(
        int id,
        List<Predicate> predicates,
        Set<String> ruleCodes,
        long cacheHitCount
    ) {}
}
```

**Location:** Find `BaseConditionEvaluator.java` (in `/helios-evaluator/`)
**Lines to modify:** Add methods at end of class

---

### 3.5 helios-service Module

#### 3.5.1 Create New REST Endpoints

**File:** `/helios-service/src/main/java/com/helios/ruleengine/service/rest/RuleManagementResource.java`

```java
/**
 * REST API for rule management and introspection.
 */
@Path("/api/v1/rules")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class RuleManagementResource {

    @Inject
    IEngineModelManager modelManager;

    /**
     * GET /api/v1/rules - List all logical rules with metadata
     */
    @GET
    public Response listRules(
        @QueryParam("enabled") Boolean enabled,
        @QueryParam("tag") String tag,
        @QueryParam("search") String searchTerm
    ) {
        EngineModel model = modelManager.getEngineModel();
        Collection<RuleMetadata> allRules = model.getAllRuleMetadata();

        // Apply filters
        Stream<RuleMetadata> filtered = allRules.stream();
        if (enabled != null) {
            filtered = filtered.filter(r -> r.enabled().equals(enabled));
        }
        if (tag != null) {
            filtered = filtered.filter(r -> r.tags().contains(tag));
        }
        if (searchTerm != null) {
            filtered = filtered.filter(r ->
                r.ruleCode().contains(searchTerm) ||
                r.description().contains(searchTerm)
            );
        }

        return Response.ok(filtered.collect(Collectors.toList())).build();
    }

    /**
     * GET /api/v1/rules/{ruleCode} - Get detailed rule info
     */
    @GET
    @Path("/{ruleCode}")
    public Response getRule(@PathParam("ruleCode") String ruleCode) {
        EngineModel model = modelManager.getEngineModel();
        RuleMetadata metadata = model.getRuleMetadata(ruleCode);

        if (metadata == null) {
            return Response.status(404)
                .entity(Map.of("error", "Rule not found"))
                .build();
        }

        // Enrich with compilation details
        Map<String, Object> response = new HashMap<>();
        response.put("metadata", metadata);
        response.put("combinationIds", model.getCombinationIdsForRule(ruleCode));
        response.put("predicates", getPredicatesForRule(model, ruleCode));

        return Response.ok(response).build();
    }

    /**
     * GET /api/v1/rules/conflicts - Analyze rule conflicts
     */
    @GET
    @Path("/conflicts")
    public Response analyzeConflicts() {
        EngineModel model = modelManager.getEngineModel();
        RuleConflictAnalyzer analyzer = new RuleConflictAnalyzer();
        ConflictReport report = analyzer.analyzeConflicts(model);
        return Response.ok(report).build();
    }

    /**
     * POST /api/v1/rules/validate - Validate rules without compilation
     */
    @POST
    @Path("/validate")
    public Response validateRules(String rulesJson) {
        // Implementation calls IRuleCompiler.validateRules()
        return Response.ok().build();
    }

    private List<Predicate> getPredicatesForRule(EngineModel model, String ruleCode) {
        Set<Integer> combinationIds = model.getCombinationIdsForRule(ruleCode);
        Set<Predicate> predicates = new HashSet<>();

        for (int combId : combinationIds) {
            IntList predIds = model.getCombinationPredicateIds(combId);
            for (int predId : predIds) {
                predicates.add(model.getPredicate(predId));
            }
        }

        return new ArrayList<>(predicates);
    }
}
```

**TODO: Create this file** ✅ NEW FILE

---

**File:** `/helios-service/src/main/java/com/helios/ruleengine/service/rest/RuleEvaluationResource.java`

**TODO: Add new endpoints** 📝 MODIFY EXISTING

```java
@Path("/api/v1/evaluate")
public class RuleEvaluationResource {

    // Existing: POST /evaluate
    @POST
    public Response evaluate(Event event) {
        // Existing implementation
    }

    // NEW: POST /evaluate/trace - Evaluate with detailed trace
    @POST
    @Path("/trace")
    public Response evaluateWithTrace(Event event) {
        IRuleEvaluator evaluator = getOrCreateEvaluator();
        EvaluationResult result = evaluator.evaluateWithTrace(event);
        return Response.ok(result).build();
    }

    // NEW: POST /evaluate/explain/{ruleCode} - Explain why rule matched/didn't match
    @POST
    @Path("/explain/{ruleCode}")
    public Response explainRule(@PathParam("ruleCode") String ruleCode, Event event) {
        IRuleEvaluator evaluator = getOrCreateEvaluator();
        ExplanationResult explanation = evaluator.explainRule(event, ruleCode);
        return Response.ok(explanation).build();
    }

    // NEW: POST /evaluate/batch - Batch evaluation
    @POST
    @Path("/batch")
    public Response evaluateBatch(List<Event> events) {
        IRuleEvaluator evaluator = getOrCreateEvaluator();
        List<MatchResult> results = evaluator.evaluateBatch(events);

        // Aggregate statistics
        Map<String, Object> response = new HashMap<>();
        response.put("results", results);
        response.put("totalEvents", events.size());
        response.put("avgLatencyNanos", calculateAvgLatency(results));

        return Response.ok(response).build();
    }
}
```

**Location:** `/helios-service/src/main/java/com/helios/ruleengine/service/rest/RuleEvaluationResource.java`
**Lines to modify:** Add methods after line 30 (after existing `evaluate` method)

---

**File:** `/helios-service/src/main/java/com/helios/ruleengine/service/rest/CompilationResource.java`

```java
/**
 * REST API for compilation insights and control.
 */
@Path("/api/v1/compilation")
@Produces(MediaType.APPLICATION_JSON)
public class CompilationResource {

    @Inject
    IRuleCompiler compiler;

    /**
     * GET /api/v1/compilation/stats - Get last compilation statistics
     */
    @GET
    @Path("/stats")
    public Response getCompilationStats() {
        EngineModel model = modelManager.getEngineModel();
        EngineStats stats = model.getStats();

        Map<String, Object> response = new HashMap<>();
        response.put("stats", stats);
        response.put("compilationTimeMs",
            TimeUnit.NANOSECONDS.toMillis(stats.compilationTimeNanos()));
        response.put("metadata", stats.metadata());

        return Response.ok(response).build();
    }

    /**
     * GET /api/v1/compilation/dictionaries - Get dictionary encodings
     */
    @GET
    @Path("/dictionaries")
    public Response getDictionaries() {
        EngineModel model = modelManager.getEngineModel();

        Map<String, Object> response = new HashMap<>();
        response.put("fields", dictionaryToMap(model.getFieldDictionary()));
        response.put("values", dictionaryToMap(model.getValueDictionary()));

        return Response.ok(response).build();
    }

    /**
     * GET /api/v1/compilation/predicates - Get all unique predicates
     */
    @GET
    @Path("/predicates")
    public Response getPredicates() {
        EngineModel model = modelManager.getEngineModel();
        Predicate[] predicates = model.getUniquePredicates();

        List<Map<String, Object>> enrichedPredicates = new ArrayList<>();
        for (int i = 0; i < predicates.length; i++) {
            Predicate p = predicates[i];
            Map<String, Object> enriched = new HashMap<>();
            enriched.put("id", i);
            enriched.put("fieldName", model.getFieldDictionary().decode(p.fieldId()));
            enriched.put("operator", p.operator().toString());
            enriched.put("value", decodeValue(model, p.value()));
            enriched.put("weight", p.weight());
            enriched.put("selectivity", p.selectivity());
            enriched.put("rulesUsing", model.getRulesUsingPredicate(i).size());

            enrichedPredicates.add(enriched);
        }

        return Response.ok(enrichedPredicates).build();
    }

    private Map<Integer, String> dictionaryToMap(Dictionary dict) {
        Map<Integer, String> map = new HashMap<>();
        for (int i = 0; i < dict.size(); i++) {
            map.put(i, dict.decode(i));
        }
        return map;
    }

    private Object decodeValue(EngineModel model, Object value) {
        if (value instanceof Integer) {
            String decoded = model.getValueDictionary().decode((Integer) value);
            return decoded != null ? decoded : value;
        }
        return value;
    }
}
```

**TODO: Create this file** ✅ NEW FILE

---

**File:** `/helios-service/src/main/java/com/helios/ruleengine/service/rest/MonitoringResource.java`

```java
/**
 * REST API for real-time monitoring and metrics.
 */
@Path("/api/v1/monitoring")
@Produces(MediaType.APPLICATION_JSON)
public class MonitoringResource {

    @Inject
    RuleEvaluationService evaluationService;

    /**
     * GET /api/v1/monitoring/metrics - Get current evaluator metrics
     */
    @GET
    @Path("/metrics")
    public Response getMetrics() {
        IRuleEvaluator evaluator = evaluationService.getEvaluator();
        Map<String, Object> metrics = evaluator.getDetailedMetrics();
        return Response.ok(metrics).build();
    }

    /**
     * GET /api/v1/monitoring/performance - Get performance breakdown
     */
    @GET
    @Path("/performance")
    public Response getPerformance() {
        EvaluatorMetrics metrics = evaluationService.getEvaluator().getMetrics();

        Map<String, Object> response = new HashMap<>();
        response.put("latencyP50Micros", metrics.getP50Micros());
        response.put("latencyP95Micros", metrics.getP95Micros());
        response.put("latencyP99Micros", metrics.getP99Micros());
        response.put("totalEvaluations", metrics.getTotalEvaluations());
        response.put("avgPredicatesEvaluated", metrics.getAvgPredicatesEvaluated());

        return Response.ok(response).build();
    }

    /**
     * GET /api/v1/monitoring/cache - Get cache statistics
     */
    @GET
    @Path("/cache")
    public Response getCacheStats() {
        IRuleEvaluator evaluator = evaluationService.getEvaluator();

        Map<String, Object> cacheStats = new HashMap<>();

        if (evaluator.getBaseConditionEvaluator() != null) {
            Map<String, Object> baseCache =
                evaluator.getBaseConditionEvaluator().getMetrics();
            cacheStats.put("baseCondition", baseCache);
        }

        cacheStats.put("eligiblePredicateSet", Map.of(
            "size", evaluator.getModel().getEligiblePredicateSetCache().estimatedSize(),
            "maxSize", evaluator.getModel().getEligiblePredicateCacheMaxSize(),
            "hitRate", calculateHitRate(evaluator.getMetrics())
        ));

        return Response.ok(cacheStats).build();
    }

    private double calculateHitRate(EvaluatorMetrics metrics) {
        long hits = metrics.eligibleSetCacheHits.sum();
        long misses = metrics.eligibleSetCacheMisses.sum();
        return (hits + misses == 0) ? 0.0 : (double) hits / (hits + misses);
    }
}
```

**TODO: Create this file** ✅ NEW FILE

---

## 4. API Contract Specifications

### 4.1 REST API Summary

Base URL: `http://localhost:8080/api/v1`

#### Rule Management Endpoints

| Method | Endpoint | Description | Request Body | Response |
|--------|----------|-------------|--------------|----------|
| GET | `/rules` | List all rules with filters | N/A | `List<RuleMetadata>` |
| GET | `/rules/{ruleCode}` | Get rule details | N/A | `RuleDetail` |
| GET | `/rules/conflicts` | Analyze rule conflicts | N/A | `ConflictReport` |
| POST | `/rules/validate` | Validate rules JSON | Rules JSON | `ValidationReport` |

#### Evaluation Endpoints

| Method | Endpoint | Description | Request Body | Response |
|--------|----------|-------------|--------------|----------|
| POST | `/evaluate` | Standard evaluation | `Event` | `MatchResult` |
| POST | `/evaluate/trace` | Evaluation with trace | `Event` | `EvaluationResult` |
| POST | `/evaluate/explain/{ruleCode}` | Explain match/no-match | `Event` | `ExplanationResult` |
| POST | `/evaluate/batch` | Batch evaluation | `List<Event>` | `BatchResult` |

#### Compilation Endpoints

| Method | Endpoint | Description | Request Body | Response |
|--------|----------|-------------|--------------|----------|
| GET | `/compilation/stats` | Compilation statistics | N/A | `EngineStats` |
| GET | `/compilation/dictionaries` | Dictionary encodings | N/A | `DictionaryMap` |
| GET | `/compilation/predicates` | All predicates | N/A | `List<PredicateDetail>` |

#### Monitoring Endpoints

| Method | Endpoint | Description | Request Body | Response |
|--------|----------|-------------|--------------|----------|
| GET | `/monitoring/metrics` | Current metrics | N/A | `MetricsSnapshot` |
| GET | `/monitoring/performance` | Performance stats | N/A | `PerformanceReport` |
| GET | `/monitoring/cache` | Cache statistics | N/A | `CacheStats` |

---

### 4.2 Data Models

#### RuleMetadata (Enhanced)

```json
{
  "ruleCode": "RULE_12453",
  "description": "High-value customer upsell",
  "conditions": [
    {
      "field": "CUSTOMER_SEGMENT",
      "operator": "EQUAL_TO",
      "value": "PREMIUM"
    },
    {
      "field": "LIFETIME_VALUE",
      "operator": "GREATER_THAN_OR_EQUAL",
      "value": 10000.0
    }
  ],
  "priority": 100,
  "enabled": true,
  "createdBy": "user@company.com",
  "createdAt": "2025-01-15T10:00:00Z",
  "lastModifiedBy": "user@company.com",
  "lastModifiedAt": "2025-10-20T14:32:00Z",
  "version": 3,
  "tags": ["upsell", "premium"],
  "labels": {
    "team": "marketing",
    "region": "US"
  },
  "combinationIds": [42, 43],
  "estimatedSelectivity": 23,
  "isVectorizable": true,
  "compilationStatus": "OK"
}
```

#### EvaluationTrace

```json
{
  "eventId": "evt_12345",
  "totalDurationNanos": 420000,
  "dictEncodingNanos": 20000,
  "baseConditionNanos": 80000,
  "predicateEvalNanos": 180000,
  "counterUpdateNanos": 120000,
  "matchDetectionNanos": 20000,
  "predicateOutcomes": [
    {
      "predicateId": 5,
      "fieldName": "CUSTOMER_SEGMENT",
      "operator": "EQUAL_TO",
      "expectedValue": "PREMIUM",
      "actualValue": "PREMIUM",
      "matched": true,
      "evaluationNanos": 5000
    },
    {
      "predicateId": 12,
      "fieldName": "LIFETIME_VALUE",
      "operator": "GREATER_THAN_OR_EQUAL",
      "expectedValue": 10000.0,
      "actualValue": 15000.0,
      "matched": true,
      "evaluationNanos": 8000
    }
  ],
  "ruleDetails": [
    {
      "combinationId": 42,
      "ruleCode": "RULE_12453",
      "priority": 100,
      "predicatesMatched": 5,
      "predicatesRequired": 5,
      "finalMatch": true,
      "failedPredicates": []
    }
  ],
  "baseConditionCacheHit": true,
  "eligibleRulesCount": 4,
  "matchedRuleCodes": ["RULE_12453"]
}
```

#### ConflictReport

```json
{
  "conflicts": [
    {
      "ruleCode1": "RULE_12453",
      "ruleCode2": "RULE_12458",
      "overlapPercentage": 0.78,
      "priority1": 100,
      "priority2": 80,
      "sharedConditions": 3,
      "uniqueToRule1": 2,
      "uniqueToRule2": 1
    }
  ]
}
```

---

## 5. Mock UI Requirements

### 5.1 Technology Stack

**Frontend Framework:** React 18+ with TypeScript
**State Management:** Zustand or Redux Toolkit
**UI Component Library:** Ant Design or Material-UI
**Data Visualization:** Recharts or D3.js
**HTTP Client:** Axios
**Build Tool:** Vite

### 5.2 UI Module Structure

```
helios-ui/
├── pom.xml (Maven frontend plugin)
├── package.json
├── vite.config.ts
├── tsconfig.json
├── public/
│   └── index.html
└── src/
    ├── main.tsx (Entry point)
    ├── App.tsx (Root component)
    ├── api/
    │   ├── client.ts (Axios instance)
    │   ├── rules.ts (Rule management API)
    │   ├── evaluation.ts (Evaluation API)
    │   ├── compilation.ts (Compilation API)
    │   └── monitoring.ts (Monitoring API)
    ├── components/
    │   ├── layout/
    │   │   ├── Header.tsx
    │   │   ├── Sidebar.tsx
    │   │   └── Footer.tsx
    │   ├── rules/
    │   │   ├── RuleList.tsx
    │   │   ├── RuleDetail.tsx
    │   │   ├── RuleEditor.tsx
    │   │   ├── ConflictAnalyzer.tsx
    │   │   └── RuleCard.tsx
    │   ├── evaluation/
    │   │   ├── EvaluationConsole.tsx
    │   │   ├── TraceViewer.tsx
    │   │   ├── ExplainPanel.tsx
    │   │   └── BatchTester.tsx
    │   ├── compilation/
    │   │   ├── CompilationDashboard.tsx
    │   │   ├── PipelineVisualization.tsx
    │   │   ├── DictionaryInspector.tsx
    │   │   └── PredicateExplorer.tsx
    │   └── monitoring/
    │       ├── MetricsDashboard.tsx
    │       ├── PerformanceChart.tsx
    │       ├── CacheHeatmap.tsx
    │       └── AlertsPanel.tsx
    ├── pages/
    │   ├── HomePage.tsx
    │   ├── RulesPage.tsx
    │   ├── EvaluationPage.tsx
    │   ├── CompilationPage.tsx
    │   └── MonitoringPage.tsx
    ├── types/
    │   ├── rule.ts
    │   ├── evaluation.ts
    │   ├── compilation.ts
    │   └── metrics.ts
    ├── hooks/
    │   ├── useRules.ts
    │   ├── useEvaluation.ts
    │   └── useMetrics.ts
    └── utils/
        ├── formatters.ts
        ├── validators.ts
        └── constants.ts
```

### 5.3 Key UI Views

#### 5.3.1 Rules Dashboard (Logical View)

**Components:**
- `RuleList`: Hierarchical tree view with search/filter
- `RuleCard`: Expandable card showing rule details
- `ConflictAnalyzer`: Conflict detection panel

**Features:**
- Multi-select for bulk operations
- Real-time search (rule code, description, conditions)
- Filters: enabled/disabled, tags, priority range
- Sort by: priority, match rate, last modified

**API Calls:**
- `GET /api/v1/rules` (on mount, with filters)
- `GET /api/v1/rules/{ruleCode}` (on expand)
- `GET /api/v1/rules/conflicts` (on demand)

---

#### 5.3.2 Compilation Pipeline View

**Components:**
- `CompilationDashboard`: Summary statistics
- `PipelineVisualization`: 7-stage pipeline graph
- `DictionaryInspector`: Field/value encoding tables
- `PredicateExplorer`: Predicate usage heatmap

**Features:**
- Progressive compilation status (if listener implemented)
- Deduplication rate visualization (before/after)
- Dictionary encoding efficiency metrics
- Predicate reuse analysis

**API Calls:**
- `GET /api/v1/compilation/stats`
- `GET /api/v1/compilation/dictionaries`
- `GET /api/v1/compilation/predicates`

---

#### 5.3.3 Evaluation Debugger

**Components:**
- `EvaluationConsole`: JSON input for event
- `TraceViewer`: Step-by-step execution DAG
- `ExplainPanel`: "Why did this match?" explainer

**Features:**
- JSON editor with syntax highlighting
- Sample event templates
- Interactive DAG (click nodes to see details)
- Predicate-level timing breakdown

**API Calls:**
- `POST /api/v1/evaluate/trace` (with event)
- `POST /api/v1/evaluate/explain/{ruleCode}` (with event)

**Mock Implementation:**
```tsx
const EvaluationConsole: React.FC = () => {
  const [event, setEvent] = useState<Event>({
    eventId: "test_001",
    eventType: "user_action",
    attributes: {
      customer_segment: "premium",
      lifetime_value: 15000.0,
      region: "US"
    }
  });

  const [trace, setTrace] = useState<EvaluationTrace | null>(null);

  const handleEvaluate = async () => {
    const response = await evaluationApi.evaluateWithTrace(event);
    setTrace(response.trace);
  };

  return (
    <div>
      <JsonEditor value={event} onChange={setEvent} />
      <Button onClick={handleEvaluate}>Evaluate</Button>
      {trace && <TraceViewer trace={trace} />}
    </div>
  );
};
```

---

#### 5.3.4 Monitoring Dashboard

**Components:**
- `MetricsDashboard`: Real-time metrics overview
- `PerformanceChart`: Latency histogram (P50, P95, P99)
- `CacheHeatmap`: Cache hit rates over time

**Features:**
- Auto-refresh every 5 seconds
- Latency trend line (last 1 hour)
- Cache effectiveness gauge
- Alert thresholds (P99 > 1ms warning)

**API Calls:**
- `GET /api/v1/monitoring/metrics` (polling)
- `GET /api/v1/monitoring/performance`
- `GET /api/v1/monitoring/cache`

**Mock Implementation:**
```tsx
const MetricsDashboard: React.FC = () => {
  const { data: metrics, isLoading } = useMetrics({ refreshInterval: 5000 });

  if (isLoading) return <Spinner />;

  return (
    <div>
      <Card title="Evaluation Metrics">
        <Statistic title="Total Evaluations" value={metrics.totalEvaluations} />
        <Statistic title="Avg Latency" value={`${metrics.avgEvaluationTimeMicros} μs`} />
      </Card>

      <Card title="Latency Distribution">
        <BarChart data={metrics.latencyHistogram}>
          <XAxis dataKey="bucketMs" />
          <YAxis />
          <Bar dataKey="count" fill="#8884d8" />
        </BarChart>
      </Card>

      <Card title="Cache Performance">
        <Progress
          percent={metrics.cacheHitRate * 100}
          status={metrics.cacheHitRate > 0.9 ? "success" : "exception"}
        />
      </Card>
    </div>
  );
};
```

---

### 5.4 UI Build Integration

**File:** `/helios-ui/pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.helios</groupId>
        <artifactId>helios-rule-engine</artifactId>
        <version>1.0-SNAPSHOT</version>
    </parent>

    <artifactId>helios-ui</artifactId>
    <packaging>jar</packaging>
    <name>Helios Rule Engine - UI</name>

    <build>
        <plugins>
            <!-- Frontend Maven Plugin -->
            <plugin>
                <groupId>com.github.eirslett</groupId>
                <artifactId>frontend-maven-plugin</artifactId>
                <version>1.15.0</version>
                <executions>
                    <execution>
                        <id>install node and npm</id>
                        <goals>
                            <goal>install-node-and-npm</goal>
                        </goals>
                        <configuration>
                            <nodeVersion>v20.10.0</nodeVersion>
                            <npmVersion>10.2.3</npmVersion>
                        </configuration>
                    </execution>
                    <execution>
                        <id>npm install</id>
                        <goals>
                            <goal>npm</goal>
                        </goals>
                        <configuration>
                            <arguments>install</arguments>
                        </configuration>
                    </execution>
                    <execution>
                        <id>npm build</id>
                        <goals>
                            <goal>npm</goal>
                        </goals>
                        <configuration>
                            <arguments>run build</arguments>
                        </configuration>
                    </execution>
                </executions>
            </plugin>

            <!-- Copy built UI to resources for serving -->
            <plugin>
                <artifactId>maven-resources-plugin</artifactId>
                <version>3.3.1</version>
                <executions>
                    <execution>
                        <id>copy-resources</id>
                        <phase>process-resources</phase>
                        <goals>
                            <goal>copy-resources</goal>
                        </goals>
                        <configuration>
                            <outputDirectory>${project.build.outputDirectory}/META-INF/resources</outputDirectory>
                            <resources>
                                <resource>
                                    <directory>${project.basedir}/dist</directory>
                                    <filtering>false</filtering>
                                </resource>
                            </resources>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

**TODO: Create this file** ✅ NEW FILE

---

## 6. Implementation Roadmap

### Phase 1: Core API Extensions (Week 1-2)

**Goals:**
- Add new DTOs (`RuleMetadata`, `EvaluationTrace`, `ExplanationResult`)
- Extend interfaces (`IRuleCompiler`, `IRuleEvaluator`)
- Add reverse lookup maps to `EngineModel`

**Deliverables:**
- [ ] Create `RuleMetadata.java`
- [ ] Create `EvaluationTrace.java`
- [ ] Modify `IRuleCompiler.java` (add `validateRules`, `CompilationListener`)
- [ ] Modify `IRuleEvaluator.java` (add `evaluateWithTrace`, `explainRule`)
- [ ] Modify `EngineModel.java` (add reverse maps)

**Risk:** Low (API extensions only, no behavior changes)

---

### Phase 2: Compiler Enhancements (Week 3)

**Goals:**
- Add compilation stage listeners
- Implement rule conflict analyzer
- Expose base condition sets

**Deliverables:**
- [ ] Modify `RuleCompiler.java` (add listener notifications)
- [ ] Create `RuleConflictAnalyzer.java`
- [ ] Modify `BaseConditionEvaluator.java` (add introspection methods)

**Risk:** Medium (touches compilation hot path, requires benchmarking)

**Performance Validation:**
- Run `SimpleBenchmark` before/after
- Target: <5% overhead
- If overhead >5%, make listener optional (disabled by default)

---

### Phase 3: Evaluator Enhancements (Week 4)

**Goals:**
- Add tracing to `RuleEvaluator`
- Implement `evaluateWithTrace` and `explainRule`
- Extend `EvaluationContext` for trace capture

**Deliverables:**
- [ ] Modify `RuleEvaluator.java` (add tracing flag and methods)
- [ ] Modify `EvaluationContext.java` (add trace structures)
- [ ] Implement `explainRule` logic

**Risk:** High (touches hot path, critical for performance)

**Performance Validation:**
- Tracing must be **disabled by default**
- When disabled: **zero overhead** (no allocations, no conditional checks in hot path)
- When enabled: <10% overhead acceptable (debugging use case)
- Run `SimpleBenchmark` with `-Dbench.profile=true` before/after

---

### Phase 4: REST API Implementation (Week 5)

**Goals:**
- Create new REST resources
- Implement all endpoints
- Add OpenAPI documentation

**Deliverables:**
- [ ] Create `RuleManagementResource.java`
- [ ] Modify `RuleEvaluationResource.java` (add new endpoints)
- [ ] Create `CompilationResource.java`
- [ ] Create `MonitoringResource.java`
- [ ] Generate OpenAPI spec

**Risk:** Low (service layer only)

---

### Phase 5: Mock UI Development (Week 6-8)

**Goals:**
- Build React UI with all views
- Integrate with REST API
- Deploy as static assets in `helios-service`

**Deliverables:**
- [ ] Set up React + TypeScript + Vite project
- [ ] Implement Rules Dashboard
- [ ] Implement Compilation Pipeline View
- [ ] Implement Evaluation Debugger
- [ ] Implement Monitoring Dashboard
- [ ] Configure Maven build to bundle UI

**Risk:** Low (frontend-only, no engine changes)

---

### Phase 6: Testing & Documentation (Week 9)

**Goals:**
- Comprehensive testing of new APIs
- Performance validation
- User documentation

**Deliverables:**
- [ ] Unit tests for new DTOs and APIs
- [ ] Integration tests for REST endpoints
- [ ] Performance regression test suite
- [ ] User guide for UI
- [ ] API reference documentation

**Risk:** Low

---

## 7. Performance Impact Assessment

### 7.1 Hot Path Analysis

**Critical Path (must be zero-overhead):**
1. `RuleEvaluator.evaluate()` - existing method
2. `doEvaluate()` - main evaluation logic
3. `updateCountersOptimized()` - 60-70% of CPU time
4. `detectMatchesOptimized()` - match detection

**Modifications to Hot Path:**
- ✅ **Safe:** Adding fields to `EngineModel` (read-only, no runtime overhead)
- ✅ **Safe:** Adding methods to `IRuleEvaluator` (new API, doesn't affect existing)
- ⚠️ **Risky:** Adding tracing to `doEvaluate()` (requires conditional checks)

**Mitigation for Tracing:**
```java
// GOOD: Zero overhead when disabled
if (tracingEnabled) {  // Single boolean check, JIT optimizes away
    ctx.recordPredicateOutcome(...);
}

// BAD: Allocates even when disabled
ctx.recordPredicateOutcome(...);  // Always creates objects
```

**Benchmark Validation:**
```bash
# Before changes
mvn clean package -Pbenchmark
java -jar helios-benchmarks/target/benchmarks.jar SimpleBenchmark

# After changes
mvn clean package -Pbenchmark
java -jar helios-benchmarks/target/benchmarks.jar SimpleBenchmark

# Compare throughput (events/min) and latency (P99)
# Regression threshold: <5% slowdown acceptable
```

---

### 7.2 Memory Impact

**New Data Structures:**
- `ruleCodeToCombinationIds`: O(R * C) where R=rules, C=avg combinations per rule
  - Example: 1000 rules, 2 combinations each = 2000 entries = ~64 KB
- `predicateIdToRuleCodes`: O(P * R) where P=predicates, R=avg rules per predicate
  - Example: 500 predicates, 10 rules each = 5000 entries = ~160 KB
- `ruleMetadata`: O(R)
  - Example: 1000 rules * 1 KB metadata = 1 MB

**Total Overhead Estimate:**
- 1000 rules: ~1.3 MB additional memory
- 10,000 rules: ~13 MB additional memory
- 100,000 rules: ~130 MB additional memory

**Acceptable?** ✅ Yes
- Current engine uses ~600 MB for 5K rules
- 130 MB for 100K rules is <3% overhead
- Read-only structures, no GC pressure

---

### 7.3 Compilation Time Impact

**New Compilation Steps:**
- Building reverse maps: O(R * C)
  - Negligible: <10ms for 10,000 rules
- Conflict analysis: O(R²) pairwise comparison
  - **Expensive:** 10,000 rules = 50M comparisons
  - **Mitigation:** Make conflict analysis **optional** (on-demand only)

**Recommendation:**
- Do NOT run conflict analysis during every compilation
- Expose as separate API: `POST /api/v1/rules/conflicts`
- Use sampling for large rule sets (analyze top 1000 rules only)

---

## 8. Summary of Modifications

### Files to CREATE (New)

| File | Purpose | Lines (Est.) |
|------|---------|--------------|
| `RuleMetadata.java` | Enhanced rule metadata DTO | ~50 |
| `EvaluationTrace.java` | Evaluation trace DTO | ~80 |
| `RuleConflictAnalyzer.java` | Conflict detection | ~150 |
| `RuleManagementResource.java` | REST API for rules | ~200 |
| `CompilationResource.java` | REST API for compilation | ~150 |
| `MonitoringResource.java` | REST API for monitoring | ~100 |
| `helios-ui/pom.xml` | UI build configuration | ~80 |
| `helios-ui/src/**/*.tsx` | React UI components | ~3000 |

**Total New Files:** ~8 Java files + ~30 TypeScript files

---

### Files to MODIFY (Existing)

| File | Changes | Risk | Lines Changed |
|------|---------|------|---------------|
| `IRuleCompiler.java` | Add methods | Low | +30 |
| `IRuleEvaluator.java` | Add methods | Low | +20 |
| `EngineModel.java` | Add reverse maps | Low | +100 |
| `RuleCompiler.java` | Add listener notifications | Medium | +50 |
| `RuleEvaluator.java` | Add tracing | **High** | +150 |
| `EvaluationContext.java` | Add trace fields | Medium | +80 |
| `BaseConditionEvaluator.java` | Add introspection | Low | +40 |
| `RuleEvaluationResource.java` | Add endpoints | Low | +60 |

**Total Modified Files:** ~8 files

---

### Performance Impact Summary

| Component | Overhead (Disabled) | Overhead (Enabled) | Risk |
|-----------|--------------------|--------------------|------|
| Reverse Maps | <1% | N/A | ✅ Low |
| Compilation Listener | <2% | <5% | ✅ Low |
| Evaluation Tracing | **0%** (critical) | <10% | ⚠️ High |
| Conflict Analysis | N/A (on-demand) | ~500ms for 1K rules | ✅ Low |

**Overall Assessment:** ✅ **Acceptable** if tracing is disabled by default

---

## 9. Next Steps

### Before Implementation

1. ✅ **Review this specification** with stakeholders
2. ✅ **Approve API contracts** (DTOs, REST endpoints)
3. ✅ **Validate performance assumptions** with prototype

### During Implementation

1. **Phase 1:** Create DTOs and interfaces (no behavior changes)
2. **Phase 2:** Add compiler enhancements (benchmark after)
3. **Phase 3:** Add evaluator tracing (**critical: benchmark before/after**)
4. **Phase 4:** Implement REST APIs
5. **Phase 5:** Build mock UI
6. **Phase 6:** Test, document, deploy

### After Implementation

1. **Performance Testing:** Run full benchmark suite
2. **Load Testing:** Validate throughput at 20M events/min
3. **User Acceptance Testing:** Validate UI with end users
4. **Documentation:** Update README, API docs, user guide

---

## Appendix A: File Locations Reference

```
helios-rule-engine/
├── helios-api/
│   └── src/main/java/com/helios/ruleengine/
│       ├── api/
│       │   ├── IRuleCompiler.java ← MODIFY
│       │   ├── IRuleEvaluator.java ← MODIFY
│       │   └── model/
│       │       ├── RuleMetadata.java ← CREATE
│       │       ├── EvaluationTrace.java ← CREATE
│       │       └── RuleDefinition.java (existing)
│       └── runtime/model/
│           └── EngineModel.java ← MODIFY
│
├── helios-compiler/
│   └── src/main/java/com/helios/ruleengine/compiler/
│       ├── RuleCompiler.java ← MODIFY
│       └── analysis/
│           └── RuleConflictAnalyzer.java ← CREATE
│
├── helios-evaluator/
│   └── src/main/java/com/helios/ruleengine/runtime/
│       ├── evaluation/
│       │   ├── RuleEvaluator.java ← MODIFY
│       │   └── BaseConditionEvaluator.java ← MODIFY
│       └── context/
│           └── EvaluationContext.java ← MODIFY
│
├── helios-service/
│   └── src/main/java/com/helios/ruleengine/service/rest/
│       ├── RuleManagementResource.java ← CREATE
│       ├── RuleEvaluationResource.java ← MODIFY
│       ├── CompilationResource.java ← CREATE
│       └── MonitoringResource.java ← CREATE
│
└── helios-ui/ ← CREATE (entire module)
    ├── pom.xml
    ├── package.json
    └── src/
        └── (React application)
```

---

**END OF SPECIFICATION**

---

## Document Control

**Version History:**

| Version | Date | Author | Changes |
|---------|------|--------|---------|
| 1.0 | 2025-12-25 | Claude | Initial specification |

**Approval:**

- [ ] Technical Lead Review
- [ ] Architecture Review
- [ ] Performance Team Review
- [ ] Product Owner Approval

**Next Review Date:** 2026-01-15
