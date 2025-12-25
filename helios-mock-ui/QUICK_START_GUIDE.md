# Helios Rule Engine UI - Quick Start Guide

## 🚀 For Engineers: Getting Started

### View the Mock UI

The UI is already built and ready to view. Simply run the application:

```bash
npm install
npm run build
# Then preview the built application
```

Navigate through the 5 tabs to see all features:
1. **Rules** - Browse existing rules
2. **Rule Builder** - Create new rules visually
3. **Compilation** - See compilation pipeline
4. **Evaluation & Debug** - Test and debug rules
5. **Monitoring** - Performance dashboard

---

## 📋 Priority Checklist for Rule Engine Team

### Phase 1: Foundation (Week 1-4)

#### Week 1: Data Model Extensions
- [ ] Add `RuleMetadata` interface to rule storage
- [ ] Extend rule parser to accept new metadata fields
- [ ] Update rule builder to preserve metadata
- [ ] Add version tracking to rules
- [ ] Implement audit logging

**Files to create:**
- `src/engine/models/RuleMetadata.ts`
- `src/engine/models/RuleVersion.ts`
- `src/engine/storage/AuditLog.ts`

#### Week 2: Evaluation Tracing
- [ ] Add `EvaluationTrace` data structure
- [ ] Implement tracing mode toggle (production vs debug)
- [ ] Add timing instrumentation to evaluation pipeline
- [ ] Capture condition-level results
- [ ] Track cache hits/misses

**Files to modify:**
- `src/engine/evaluator/Evaluator.ts` - Add tracing hooks
- `src/engine/evaluator/ConditionEvaluator.ts` - Capture results

**Files to create:**
- `src/engine/tracing/EvaluationTrace.ts`
- `src/engine/tracing/TracingConfig.ts`

#### Week 3: Compilation Metrics
- [ ] Add instrumentation to each compilation stage
- [ ] Collect timing and resource metrics
- [ ] Calculate deduplication effectiveness
- [ ] Track vectorization coverage
- [ ] Measure memory savings

**Files to modify:**
- `src/engine/compiler/Parser.ts` - Add metrics
- `src/engine/compiler/DictionaryEncoder.ts` - Track encodings
- `src/engine/compiler/Deduplicator.ts` - Measure savings
- `src/engine/compiler/Vectorizer.ts` - Track coverage

**Files to create:**
- `src/engine/compiler/CompilationMetrics.ts`

#### Week 4: REST API
- [ ] Set up API server (Express/FastAPI)
- [ ] Implement rule CRUD endpoints
- [ ] Add evaluation endpoint with tracing
- [ ] Add compilation trigger endpoint
- [ ] Add metrics export endpoint

**Endpoints to implement:**
```
GET    /api/v1/rules
POST   /api/v1/rules
GET    /api/v1/rules/:id
PUT    /api/v1/rules/:id
DELETE /api/v1/rules/:id
POST   /api/v1/evaluate
POST   /api/v1/compile
GET    /api/v1/metrics
```

**Files to create:**
- `src/api/server.ts`
- `src/api/routes/rules.ts`
- `src/api/routes/evaluation.ts`
- `src/api/routes/metrics.ts`

---

### Phase 2: Enhanced Debugging (Week 5-8)

#### Week 5-6: Detailed Tracing
- [ ] Implement per-condition timing
- [ ] Add "why did this fail?" explanation engine
- [ ] Calculate deltas for failed numeric conditions
- [ ] Add suggestion generator
- [ ] Implement historical context lookup

**Files to create:**
- `src/engine/tracing/ExplanationEngine.ts`
- `src/engine/tracing/SuggestionGenerator.ts`

#### Week 7: Performance Profiling
- [ ] Add per-rule profiling mode
- [ ] Implement time breakdown collection
- [ ] Build bottleneck identification
- [ ] Create recommendation engine

**Files to create:**
- `src/engine/profiling/RuleProfiler.ts`
- `src/engine/profiling/RecommendationEngine.ts`

#### Week 8: Test Framework
- [ ] Implement test case storage
- [ ] Add batch evaluation mode
- [ ] Build assertion framework
- [ ] Create aggregate statistics

**Files to create:**
- `src/engine/testing/TestCase.ts`
- `src/engine/testing/TestSuite.ts`
- `src/engine/testing/BatchEvaluator.ts`

---

### Phase 3: Optimization (Week 9-12)

#### Week 9-10: Analysis Tools
- [ ] Implement conflict detection algorithm
- [ ] Build vectorization analyzer
- [ ] Create dictionary inspector API
- [ ] Add performance heatmap generation

**Files to create:**
- `src/engine/analysis/ConflictDetector.ts`
- `src/engine/analysis/VectorizationAnalyzer.ts`
- `src/engine/analysis/DictionaryInspector.ts`

#### Week 11-12: Monitoring
- [ ] Set up metrics collection pipeline
- [ ] Implement sliding window aggregation
- [ ] Add anomaly detection
- [ ] Create Prometheus exporter

**Files to create:**
- `src/engine/monitoring/MetricsCollector.ts`
- `src/engine/monitoring/AnomalyDetector.ts`
- `src/engine/monitoring/PrometheusExporter.ts`

---

## 🔑 Key Integration Points

### 1. Tracing Mode Toggle

Add to your evaluator:

```typescript
interface EvaluationOptions {
  mode: 'production' | 'debug';
  traceConditions?: boolean;
  captureTimings?: boolean;
}

class RuleEvaluator {
  evaluate(event: Event, options: EvaluationOptions = { mode: 'production' }) {
    if (options.mode === 'debug') {
      return this.evaluateWithTracing(event, options);
    }
    return this.evaluateFast(event);
  }
}
```

### 2. Compilation Metrics Hook

Add to your compiler:

```typescript
class RuleCompiler {
  compile(rules: Rule[]): CompiledRuleSet {
    const metrics = new CompilationMetrics();
    
    metrics.startStage('parsing');
    const parsed = this.parse(rules);
    metrics.endStage('parsing', { rulesProcessed: rules.length });
    
    metrics.startStage('encoding');
    const encoded = this.encodeDictionaries(parsed);
    metrics.endStage('encoding', { dictionariesCreated: encoded.dictionaries.size });
    
    // ... more stages
    
    return { compiled, metrics };
  }
}
```

### 3. REST API Example

```typescript
// Express.js example
app.post('/api/v1/evaluate', async (req, res) => {
  const { event, mode = 'production' } = req.body;
  
  const result = await evaluator.evaluate(event, { mode });
  
  if (mode === 'debug') {
    res.json({
      matched: result.matched,
      trace: result.trace,
      timings: result.timings,
      explanation: result.explanation
    });
  } else {
    res.json({ matched: result.matched });
  }
});
```

### 4. Metrics Collection

```typescript
class MetricsCollector {
  private counters = new Map<string, AtomicCounter>();
  private histograms = new Map<string, Histogram>();
  
  recordEvaluation(ruleId: string, latencyMs: number, matched: boolean) {
    this.counters.get(`rule.${ruleId}.total`)?.increment();
    if (matched) {
      this.counters.get(`rule.${ruleId}.matched`)?.increment();
    }
    this.histograms.get(`rule.${ruleId}.latency`)?.record(latencyMs);
  }
  
  exportPrometheus(): string {
    // Format metrics for Prometheus
  }
}
```

---

## 📊 Performance Targets

Based on the spec, the system must handle:

- **15M+ events/minute** throughput
- **<0.8ms P99** latency
- **<6GB** memory footprint
- **>90%** cache hit rate

### Optimization Checklist

- [ ] Use lock-free data structures for metrics
- [ ] Implement async metrics export
- [ ] Add feature flags for tracing
- [ ] Use sampling for high-volume scenarios
- [ ] Optimize hot paths (disable tracing in production)
- [ ] Implement metrics buffering
- [ ] Use batch operations where possible

---

## 🧪 Testing Strategy

### Unit Tests
```typescript
describe('EvaluationTracing', () => {
  it('captures condition-level results in debug mode', () => {
    const result = evaluator.evaluate(event, { mode: 'debug' });
    expect(result.trace.conditions).toBeDefined();
    expect(result.trace.conditions[0]).toHaveProperty('passed');
  });
  
  it('skips tracing in production mode', () => {
    const result = evaluator.evaluate(event, { mode: 'production' });
    expect(result.trace).toBeUndefined();
  });
});
```

### Integration Tests
```typescript
describe('Rule API', () => {
  it('creates a rule with metadata', async () => {
    const response = await request(app)
      .post('/api/v1/rules')
      .send({ name: 'Test Rule', priority: 100, conditions: [...] });
    
    expect(response.status).toBe(201);
    expect(response.body.id).toBeDefined();
    expect(response.body.version).toBe(1);
  });
});
```

### Performance Tests
```typescript
describe('Performance', () => {
  it('maintains throughput with tracing disabled', async () => {
    const startTime = Date.now();
    const count = 100000;
    
    for (let i = 0; i < count; i++) {
      await evaluator.evaluate(event, { mode: 'production' });
    }
    
    const duration = Date.now() - startTime;
    const throughput = (count / duration) * 1000 * 60; // events/min
    
    expect(throughput).toBeGreaterThan(15000000);
  });
});
```

---

## 📚 Reference Documentation

### Must-Read Files
1. `RULE_ENGINE_MODIFICATIONS.md` - Complete technical spec
2. `HELIOS_UI_README.md` - UI features and implementation guide
3. `SESSION_SUMMARY.md` - Overview and deliverables

### Component Documentation
- **RuleListView**: `/src/app/components/helios/RuleListView.tsx`
- **CompilationView**: `/src/app/components/helios/CompilationView.tsx`
- **EvaluationView**: `/src/app/components/helios/EvaluationView.tsx`
- **MonitoringView**: `/src/app/components/helios/MonitoringView.tsx`
- **VisualRuleBuilder**: `/src/app/components/helios/VisualRuleBuilder.tsx`

### Mock Data
- All data structures: `/src/app/components/helios/mock-data.ts`

---

## 🤝 Team Responsibilities

### Backend Team
- Implement rule engine modifications
- Build REST API
- Set up metrics collection
- Create profiling infrastructure

### Frontend Team
- Connect UI to real API endpoints
- Add real-time data updates (WebSockets)
- Implement data caching
- Add error handling and loading states

### DevOps Team
- Set up Prometheus for metrics
- Configure Grafana dashboards
- Implement log aggregation
- Set up alerting

### QA Team
- Create test suites using the test framework
- Validate performance under load
- Test tracing overhead
- Verify metrics accuracy

---

## 📞 Need Help?

### Common Questions

**Q: How do I enable tracing without impacting production performance?**  
A: Use feature flags and mode toggles. Only enable tracing for specific test events, not all production traffic.

**Q: How should metrics be stored?**  
A: Hot metrics (last 5 min) → In-memory cache  
   Warm metrics (last 24 hours) → Time-series DB (Prometheus)  
   Cold metrics (historical) → Archive storage

**Q: What's the overhead of tracing?**  
A: Expect 2-5x slowdown in debug mode. This is acceptable for debugging specific events, but NOT for production traffic.

**Q: How do I add a new metric?**  
A: 
1. Add to `MetricsCollector` class
2. Export via Prometheus endpoint
3. Update Grafana dashboard
4. Document in API spec

---

## ✅ Success Criteria

You'll know you're done when:

- [ ] UI can fetch and display real rules from API
- [ ] Evaluation console returns real trace data
- [ ] Compilation view shows actual metrics
- [ ] Monitoring dashboard displays live data
- [ ] Performance targets are met (15M events/min, <0.8ms P99)
- [ ] All endpoints documented in OpenAPI spec
- [ ] Metrics exported to Prometheus
- [ ] Test suite has >80% coverage

---

**Good luck!** 🚀

For questions or clarifications, refer to the detailed documentation in `RULE_ENGINE_MODIFICATIONS.md`.
