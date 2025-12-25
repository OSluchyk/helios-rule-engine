# Helios Rule Engine: Required Modifications for UI Integration

This document outlines the modifications needed to the Helios rule engine to support the comprehensive UI features outlined in the specification.

---

## 1. CORE DATA MODEL ENHANCEMENTS

### 1.1 Rule Metadata Extension

**Current State**: Rules likely have minimal metadata (ID, conditions, actions, priority)

**Required Additions**:
```typescript
interface RuleMetadata {
  // Identity
  id: string;
  name: string;
  description: string;
  family: string; // Business domain grouping
  tags: string[];
  
  // Lifecycle
  status: 'active' | 'inactive' | 'draft' | 'archived';
  version: number;
  createdAt: Date;
  createdBy: string;
  lastModifiedAt: Date;
  lastModifiedBy: string;
  
  // Operational
  priority: number;
  weight: number;
  
  // Optimization hints
  dedupGroupId?: string;
  cacheKey?: string;
  estimatedSelectivity?: number;
}
```

**Implementation Impact**: Medium
- Add new fields to rule storage schema
- Extend rule parser to accept metadata
- Update rule builder to preserve metadata through compilation

---

### 1.2 Rule Versioning System

**Current State**: No version tracking

**Required Additions**:
```typescript
interface RuleVersion {
  version: number;
  rule: Rule;
  metadata: RuleMetadata;
  timestamp: Date;
  changedBy: string;
  changeDescription: string;
  performanceImpact?: {
    matchRateDelta: number;
    latencyDelta: number;
    throughputDelta: number;
  };
}

interface RuleHistory {
  ruleId: string;
  versions: RuleVersion[];
  auditLog: AuditEntry[];
}
```

**Implementation Impact**: High
- Create versioning storage layer
- Implement version diffing algorithm
- Add rollback mechanism
- Track performance deltas between versions

---

## 2. COMPILATION PIPELINE INSTRUMENTATION

### 2.1 Compilation Stage Metrics

**Current State**: Compilation happens as black box

**Required Additions**:
```typescript
interface CompilationMetrics {
  stages: {
    parsing: { durationMs: number; rulesProcessed: number; errors: number };
    dictionaryEncoding: { 
      durationMs: number; 
      dictionaries: Map<string, DictionaryStats>;
    };
    deduplication: { 
      durationMs: number; 
      inputRules: number; 
      outputGroups: number; 
      dedupRate: number;
      memorySavingsBytes: number;
    };
    soaLayout: { 
      durationMs: number; 
      cacheLineUtilization: number;
    };
    invertedIndex: { 
      durationMs: number; 
      indexSize: number;
      averageReduction: number;
    };
    simdVectorization: { 
      durationMs: number; 
      vectorizedPredicates: number; 
      scalarPredicates: number;
      vectorizationRate: number;
    };
  };
  totalDurationMs: number;
  estimatedThroughput: number;
  memoryFootprintBytes: number;
}

interface DictionaryStats {
  attribute: string;
  uniqueValues: number;
  encodingBits: number;
  memorySavingsPercent: number;
  cacheHitRate?: number;
  valueDistribution: Map<string, number>; // value -> frequency
  rulesUsingAttribute: Set<string>;
}
```

**Implementation Impact**: Medium-High
- Add instrumentation points in each compilation stage
- Collect timing and resource metrics
- Expose metrics via API endpoint

---

### 2.2 Compilation DAG Construction

**Current State**: Linear compilation flow

**Required Additions**:
```typescript
interface CompilationNode {
  id: string;
  type: 'rule' | 'dedupGroup' | 'dictionary' | 'index' | 'vector';
  name: string;
  inputs: string[]; // Node IDs
  outputs: string[]; // Node IDs
  metadata: {
    rulesAffected?: string[];
    optimizationApplied?: string;
    performanceImpact?: number;
  };
  status: 'optimized' | 'partial' | 'bottleneck';
}

interface CompilationDAG {
  nodes: CompilationNode[];
  edges: { from: string; to: string }[];
}
```

**Implementation Impact**: Medium
- Track dependencies during compilation
- Build DAG representation
- Annotate nodes with optimization status

---

## 3. RULE EVALUATION INSTRUMENTATION

### 3.1 Detailed Evaluation Tracing

**Current State**: Fast execution, minimal logging

**Required Additions**:
```typescript
interface EvaluationTrace {
  eventId: string;
  timestamp: Date;
  
  // Performance breakdown
  timings: {
    parsing: number;
    dictionaryEncoding: number;
    candidateFiltering: number;
    baseEvaluation: number;
    vectorEvaluation: number;
    actionExecution: number;
    total: number;
  };
  
  // Execution flow
  stages: {
    candidateReduction: {
      totalRules: number;
      candidateRules: number;
      reductionPercent: number;
      indexesUsed: string[];
    };
    baseEvaluation: {
      dedupGroupsEvaluated: number;
      cacheHits: number;
      cacheMisses: number;
    };
    vectorEvaluation: {
      batchSize: number;
      simdWidth: number;
      vectorOps: number;
    };
  };
  
  // Results
  matchedRules: RuleMatch[];
  nonMatchedRules: RuleNonMatch[];
  actionsExecuted: Action[];
}

interface RuleMatch {
  ruleId: string;
  ruleName: string;
  priority: number;
  conditionResults: ConditionEvaluation[];
  evalTimeMs: number;
  cacheHit: boolean;
}

interface RuleNonMatch {
  ruleId: string;
  ruleName: string;
  failedConditions: ConditionEvaluation[];
  shortCircuitedAt?: number; // Which condition index
}

interface ConditionEvaluation {
  index: number;
  type: 'base' | 'vectorized';
  attribute: string;
  operator: string;
  expectedValue: any;
  actualValue: any;
  matched: boolean;
  evalTimeNs?: number;
}
```

**Implementation Impact**: High
- Add tracing mode toggle (production vs debug)
- Instrument evaluation pipeline with timing points
- Capture condition-level results
- Expose trace via API

---

### 3.2 "Why" Explanation Engine

**Current State**: Binary match/no-match result

**Required Additions**:
```typescript
interface RuleExplanation {
  ruleId: string;
  matched: boolean;
  reason: string; // Human-readable explanation
  
  conditionBreakdown: {
    condition: string;
    passed: boolean;
    expectedValue: any;
    actualValue: any;
    delta?: number | string; // Quantified difference
    suggestion?: string; // How to make it match
  }[];
  
  historicalContext?: {
    matchRate: number; // % of events that match this rule
    averageAttributeValue: number;
    percentile: number; // Where this event ranks
  };
}
```

**Implementation Impact**: Medium
- Build explanation generator
- Add statistical tracking for context
- Generate human-readable descriptions

---

## 4. PERFORMANCE MONITORING

### 4.1 Real-Time Metrics Collection

**Current State**: Basic throughput tracking

**Required Additions**:
```typescript
interface RulePerformanceMetrics {
  ruleId: string;
  windowStart: Date;
  windowEnd: Date;
  
  evaluations: {
    total: number;
    matched: number;
    matchRate: number;
  };
  
  latency: {
    p50: number;
    p95: number;
    p99: number;
    p999: number;
    max: number;
  };
  
  cache: {
    hits: number;
    misses: number;
    hitRate: number;
  };
  
  resources: {
    cpuTimeMs: number;
    memoryBytes: number;
  };
}

interface SystemMetrics {
  timestamp: Date;
  
  throughput: {
    eventsPerMinute: number;
    rulesPerMinute: number;
  };
  
  latency: {
    p50: number;
    p95: number;
    p99: number;
    p999: number;
  };
  
  memory: {
    totalBytes: number;
    usedBytes: number;
    utilization: number;
  };
  
  cache: {
    baseConditionHitRate: number;
    dictionaryHitRate: number;
    dedupEffectiveness: number;
  };
  
  hotRules: { ruleId: string; evaluationsPerMinute: number }[];
  slowRules: { ruleId: string; p99LatencyMs: number }[];
  alerts: Alert[];
}
```

**Implementation Impact**: High
- Add metrics collection hooks in evaluation loop
- Implement sliding window aggregation
- Create metrics export endpoint (Prometheus format)
- Add anomaly detection

---

### 4.2 Rule Performance Profiler

**Current State**: No per-rule profiling

**Required Additions**:
```typescript
interface RuleProfile {
  ruleId: string;
  
  performance: {
    evaluationTime: {
      total: number;
      breakdown: {
        candidateCheck: number;
        baseConditions: number;
        vectorConditions: number;
        actionExecution: number;
      };
    };
    bottlenecks: {
      stage: string;
      timeMs: number;
      percentOfTotal: number;
    }[];
  };
  
  optimization: {
    vectorizable: boolean;
    cacheEfficiency: number;
    dedupOpportunities: number;
    indexUsage: string[];
  };
  
  recommendations: {
    priority: 'high' | 'medium' | 'low';
    type: 'split_rule' | 'add_index' | 'refactor_vectorize' | 'reorder_conditions';
    description: string;
    estimatedImpact: string;
  }[];
}
```

**Implementation Impact**: Medium
- Add profiling mode
- Implement time breakdown collection
- Build recommendation engine

---

## 5. ADVANCED FEATURES

### 5.1 Rule Conflict Detection

**Current State**: None

**Required Additions**:
```typescript
interface RuleConflict {
  type: 'overlap' | 'contradiction' | 'redundancy';
  severity: 'high' | 'medium' | 'low';
  
  rulesInvolved: string[];
  
  overlap?: {
    sharedConditions: string[];
    overlapPercent: number;
    impactedEvents: number; // How many events trigger both
  };
  
  contradiction?: {
    conflictingActions: { rule1Action: string; rule2Action: string }[];
  };
  
  suggestions: {
    type: 'merge' | 'adjust_priority' | 'add_exclusion' | 'split';
    description: string;
  }[];
}

function detectConflicts(rules: Rule[]): RuleConflict[];
```

**Implementation Impact**: High
- Implement condition overlap analysis
- Build conflict detection algorithms
- Create resolution suggestions

---

### 5.2 A/B Testing Framework

**Current State**: None

**Required Additions**:
```typescript
interface ABExperiment {
  id: string;
  name: string;
  status: 'draft' | 'running' | 'paused' | 'completed';
  
  variants: {
    name: string;
    rule: Rule;
    trafficPercent: number;
  }[];
  
  startDate: Date;
  endDate: Date;
  
  results: {
    variant: string;
    matchRate: number;
    conversionRate?: number;
    averageLatency: number;
    sampleSize: number;
  }[];
  
  statisticalSignificance?: number;
  winner?: string;
}

interface RolloutConfig {
  ruleId: string;
  schedule: {
    date: Date;
    trafficPercent: number;
  }[];
  healthChecks: {
    errorRateThreshold: number;
    latencyThreshold: number;
    autoRollbackEnabled: boolean;
  };
}
```

**Implementation Impact**: Very High
- Add traffic splitting mechanism
- Implement variant routing
- Build statistical analysis
- Create health check system
- Add rollback capability

---

### 5.3 Batch Testing & Test Suite

**Current State**: Individual event evaluation only

**Required Additions**:
```typescript
interface TestCase {
  id: string;
  name: string;
  input: Event;
  expectedMatches: string[]; // Rule IDs
  expectedNoMatches: string[]; // Rule IDs
  latencyThresholdMs?: number;
  assertions: {
    type: 'match' | 'no_match' | 'latency' | 'cache_hit' | 'custom';
    condition: any;
  }[];
}

interface TestSuite {
  id: string;
  name: string;
  tests: TestCase[];
  lastRun?: {
    timestamp: Date;
    passed: number;
    failed: number;
    duration: number;
  };
}

interface BatchEvaluationResult {
  totalTests: number;
  passed: number;
  failed: number;
  
  aggregateStats: {
    latencyDistribution: { p50: number; p95: number; p99: number };
    matchRateDistribution: Map<string, number>; // ruleId -> match count
    ruleCoverage: number; // % of rules exercised
  };
  
  failures: {
    testId: string;
    expectedMatches: string[];
    actualMatches: string[];
    latencyViolation?: boolean;
  }[];
  
  anomalies: {
    testId: string;
    type: 'latency_spike' | 'unexpected_match' | 'cache_miss';
    description: string;
  }[];
}

function runBatchEvaluation(
  events: Event[], 
  testCases?: TestCase[]
): BatchEvaluationResult;
```

**Implementation Impact**: Medium-High
- Add batch processing mode
- Implement test case storage
- Build assertion framework
- Create aggregate statistics

---

## 6. DICTIONARY & ENCODING INSPECTION

### 6.1 Dictionary Introspection API

**Current State**: Dictionaries are internal optimization detail

**Required Additions**:
```typescript
interface Dictionary {
  attribute: string;
  entries: {
    id: number;
    value: string;
    frequency: number; // How often this value appears
    rulesUsing: string[]; // Rule IDs that filter on this value
  }[];
  
  stats: {
    totalUniqueValues: number;
    encodingBits: number;
    memorySavingsPercent: number;
    cacheHitRate: number;
  };
  
  optimizationSuggestions: {
    type: 'split_high_fanout' | 'merge_low_frequency' | 'add_sub_categories';
    reason: string;
    impact: string;
  }[];
}

function getDictionary(attribute: string): Dictionary;
function getAllDictionaries(): Dictionary[];
function rebuildDictionary(attribute: string): void;
```

**Implementation Impact**: Low-Medium
- Expose dictionary internals
- Add frequency tracking
- Build optimization analyzer

---

## 7. SIMD VECTORIZATION ANALYSIS

### 7.1 Vectorization Report

**Current State**: Vectorization happens automatically

**Required Additions**:
```typescript
interface VectorizationReport {
  totalPredicates: number;
  vectorizedPredicates: number;
  scalarPredicates: number;
  vectorizationRate: number;
  
  performanceImpact: {
    speedupFactor: number; // e.g., 3.2x
    memoryBandwidthUtilization: number;
    simdWidth: number; // bits (e.g., 256 for AVX2)
  };
  
  nonVectorizablePredicates: {
    ruleId: string;
    condition: string;
    reason: 'variable_length_strings' | 'control_flow_divergence' | 'division_by_zero_risk' | 'other';
    suggestion: string;
    potentialSpeedup?: number;
  }[];
  
  autoOptimizationOpportunities: {
    ruleId: string;
    refactoring: string;
    estimatedGain: number;
  }[];
}

function getVectorizationReport(): VectorizationReport;
function autoOptimizeVectorization(ruleIds: string[]): OptimizationResult;
```

**Implementation Impact**: Medium
- Track vectorization decisions
- Annotate non-vectorizable predicates
- Build refactoring suggestions

---

## 8. API ENDPOINTS REQUIRED

### 8.1 Rule Management API
```
GET    /api/rules                         # List all rules (with filters)
GET    /api/rules/:id                     # Get rule details
POST   /api/rules                         # Create new rule
PUT    /api/rules/:id                     # Update rule
DELETE /api/rules/:id                     # Delete rule
GET    /api/rules/:id/history             # Get version history
POST   /api/rules/:id/rollback/:version   # Rollback to version
```

### 8.2 Compilation API
```
POST   /api/compile                       # Trigger recompilation
GET    /api/compile/status                # Get compilation status
GET    /api/compile/metrics               # Get compilation metrics
GET    /api/compile/dag                   # Get compilation DAG
```

### 8.3 Evaluation API
```
POST   /api/evaluate                      # Evaluate single event (with tracing)
POST   /api/evaluate/batch                # Batch evaluate events
POST   /api/evaluate/explain              # Get explanation for match/no-match
```

### 8.4 Testing API
```
GET    /api/test-suites                   # List test suites
POST   /api/test-suites                   # Create test suite
POST   /api/test-suites/:id/run           # Run test suite
GET    /api/test-suites/:id/results       # Get test results
```

### 8.5 Monitoring API
```
GET    /api/metrics                       # Get system metrics (Prometheus format)
GET    /api/metrics/rules/:id             # Get per-rule metrics
GET    /api/performance/profile           # Get performance profiles
GET    /api/performance/heatmap           # Get latency heatmap
```

### 8.6 Analysis API
```
GET    /api/analysis/conflicts            # Detect rule conflicts
GET    /api/analysis/vectorization        # Get vectorization report
GET    /api/dictionaries                  # List all dictionaries
GET    /api/dictionaries/:attribute       # Get dictionary details
```

### 8.7 Experiments API
```
GET    /api/experiments                   # List A/B experiments
POST   /api/experiments                   # Create experiment
PUT    /api/experiments/:id/status        # Start/pause/stop experiment
GET    /api/experiments/:id/results       # Get experiment results
```

---

## 9. STORAGE REQUIREMENTS

### 9.1 Persistent Storage
- **Rule Definitions**: Store with full metadata and versioning
- **Test Suites**: Store test cases and results
- **Experiments**: Store experiment configs and results
- **Audit Logs**: Store all changes with user attribution
- **Historical Metrics**: Time-series data for performance tracking

### 9.2 In-Memory Caches
- **Compiled Rules**: Fast access to compiled structures
- **Metrics Buffers**: Recent metrics for real-time display
- **Dictionary Caches**: Frequently accessed encodings

---

## 10. IMPLEMENTATION PRIORITY

### Phase 1 (Critical for MVP UI)
1. Rule metadata extension
2. Basic evaluation tracing
3. Compilation metrics collection
4. Rule management API
5. Single event evaluation with explanation

### Phase 2 (Enhanced Debugging)
6. Detailed condition-level tracing
7. Performance profiling
8. Batch testing framework
9. Test suite management

### Phase 3 (Optimization & Analysis)
10. Conflict detection
11. Vectorization analysis
12. Dictionary introspection
13. Performance heatmaps

### Phase 4 (Advanced Features)
14. A/B testing framework
15. Gradual rollout mechanism
16. Anomaly detection
17. Auto-optimization suggestions

---

## 11. PERFORMANCE CONSIDERATIONS

### 11.1 Tracing Overhead
- Implement **dual-mode execution**: production (fast) vs debug (traced)
- Use feature flags to enable/disable tracing
- Consider sampling for high-volume scenarios

### 11.2 Metrics Collection
- Use **lock-free counters** for high-frequency updates
- Aggregate metrics in sliding windows
- Offload metrics export to separate thread

### 11.3 API Performance
- Cache frequently accessed data (rule lists, metrics)
- Use pagination for large result sets
- Consider GraphQL for flexible querying

---

## 12. BACKWARD COMPATIBILITY

### 12.1 Opt-In Instrumentation
- All new features should be **opt-in** initially
- Default to fast path without instrumentation
- Allow gradual migration

### 12.2 API Versioning
- Version all API endpoints (`/api/v1/...`)
- Maintain backward compatibility for at least 2 major versions

---

## SUMMARY OF CHANGES BY COMPONENT

| Component | Change Type | Impact | Priority |
|-----------|-------------|--------|----------|
| Rule Data Model | Add metadata fields | Medium | P1 |
| Rule Storage | Add versioning support | High | P1 |
| Compiler | Add instrumentation | Medium-High | P1 |
| Evaluator | Add tracing mode | High | P1 |
| Metrics System | Build from scratch | High | P2 |
| API Layer | Build REST endpoints | Medium-High | P1 |
| Conflict Detector | Build from scratch | High | P3 |
| A/B Framework | Build from scratch | Very High | P4 |
| Test Framework | Build from scratch | Medium-High | P2 |
| Dictionary Inspector | Expose internals | Low-Medium | P3 |
| SIMD Analyzer | Expose analysis | Medium | P3 |

---

**Next Steps**: This document should be reviewed by the rule engine team to validate feasibility and refine implementation estimates. Each modification should be designed to maintain the engine's core performance characteristics while adding observability and control.
