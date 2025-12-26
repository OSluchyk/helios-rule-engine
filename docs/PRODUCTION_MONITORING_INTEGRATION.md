# Production Monitoring Integration Guide

This document outlines integration patterns for connecting Helios Rule Engine metrics to production monitoring systems.

## Current Implementation (Development)

The development environment uses:
- **REST API Endpoints**: JSON-based metrics at `/api/v1/monitoring/*`
- **Real-time UI Dashboard**: React-based monitoring view with auto-refresh
- **In-Memory Metrics**: Lightweight RuleMetricsAggregator with minimal overhead (~400KB for 1000 rules)

## Production Integration Options

### Option 1: Prometheus Integration (Recommended for Kubernetes)

**Architecture:**
```
Helios Service → Prometheus Exporter → Prometheus Server → Grafana
```

**Implementation Steps:**

1. Add Prometheus client library:
```xml
<dependency>
    <groupId>io.prometheus</groupId>
    <artifactId>simpleclient</artifactId>
    <version>0.16.0</version>
</dependency>
<dependency>
    <groupId>io.prometheus</groupId>
    <artifactId>simpleclient_hotspot</artifactId>
    <version>0.16.0</version>
</dependency>
<dependency>
    <groupId>io.prometheus</groupId>
    <artifactId>simpleclient_servlet</artifactId>
    <version>0.16.0</version>
</dependency>
```

2. Create Prometheus exporter endpoint:
```java
@Path("/metrics")
@Produces("text/plain; version=0.0.4")
public class PrometheusMetricsResource {

    @Inject
    RuleMetricsAggregator metricsAggregator;

    @GET
    public Response exportMetrics() {
        StringBuilder sb = new StringBuilder();

        // Export summary metrics
        var summary = metricsAggregator.getSummary();
        sb.append("helios_evaluations_total ").append(summary.totalEvaluations()).append("\n");
        sb.append("helios_matches_total ").append(summary.totalMatches()).append("\n");
        sb.append("helios_match_rate ").append(summary.overallMatchRate()).append("\n");
        sb.append("helios_cache_hit_rate ").append(summary.cacheHitRate()).append("\n");
        sb.append("helios_throughput_events_per_minute ").append(summary.avgEventsPerMinute()).append("\n");

        // Export per-rule metrics
        for (var hotRule : metricsAggregator.getHotRules(100)) {
            sb.append("helios_rule_evaluations_total{rule=\"")
              .append(hotRule.ruleCode()).append("\"} ")
              .append(hotRule.evaluationCount()).append("\n");
            sb.append("helios_rule_matches_total{rule=\"")
              .append(hotRule.ruleCode()).append("\"} ")
              .append(hotRule.matchCount()).append("\n");
        }

        return Response.ok(sb.toString()).build();
    }
}
```

3. Configure Prometheus scraping (`prometheus.yml`):
```yaml
scrape_configs:
  - job_name: 'helios-rule-engine'
    scrape_interval: 15s
    static_configs:
      - targets: ['helios-service:8080']
    metrics_path: '/api/v1/metrics'
```

**Pros:**
- Industry standard for Kubernetes environments
- Excellent Grafana integration
- Rich query language (PromQL)
- Built-in alerting

**Cons:**
- Requires additional infrastructure
- Pull-based model may not suit all architectures

---

### Option 2: VictoriaMetrics Integration

**Architecture:**
```
Helios Service → VictoriaMetrics Agent → VictoriaMetrics → Grafana
```

VictoriaMetrics is Prometheus-compatible but more resource-efficient. Use the same Prometheus exporter format above.

**Key Advantages:**
- Lower memory footprint than Prometheus
- Better compression
- Faster queries on large datasets
- Drop-in Prometheus replacement

**Configuration:**
```bash
# VictoriaMetrics agent config
./vmagent-prod \
  -promscrape.config=prometheus.yml \
  -remoteWrite.url=http://victoria-metrics:8428/api/v1/write
```

---

### Option 3: OpenTelemetry Metrics (Future-Proof)

**Architecture:**
```
Helios Service → OpenTelemetry Collector → [Prometheus/Jaeger/DataDog/etc]
```

Since Helios already uses OpenTelemetry for tracing, adding metrics is straightforward.

**Implementation:**

1. Add OTel metrics dependency:
```xml
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-sdk-metrics</artifactId>
    <version>1.34.1</version>
</dependency>
```

2. Create metrics bridge:
```java
@ApplicationScoped
public class OtelMetricsBridge {

    private final Meter meter;
    private final LongCounter evaluationsCounter;
    private final LongCounter matchesCounter;
    private final DoubleGauge cacheHitRateGauge;

    @Inject
    public OtelMetricsBridge(OpenTelemetry openTelemetry, RuleMetricsAggregator aggregator) {
        this.meter = openTelemetry.getMeter("helios-rule-engine");

        this.evaluationsCounter = meter.counterBuilder("helios.evaluations")
            .setDescription("Total number of rule evaluations")
            .build();

        this.matchesCounter = meter.counterBuilder("helios.matches")
            .setDescription("Total number of rule matches")
            .build();

        this.cacheHitRateGauge = meter.gaugeBuilder("helios.cache.hit_rate")
            .setDescription("Cache hit rate")
            .buildWithCallback(measurement -> {
                measurement.record(aggregator.getCacheHitRate());
            });
    }

    public void recordEvaluation(String ruleCode, boolean matched) {
        evaluationsCounter.add(1, Attributes.of(
            AttributeKey.stringKey("rule"), ruleCode,
            AttributeKey.booleanKey("matched"), matched
        ));
        if (matched) {
            matchesCounter.add(1, Attributes.of(
                AttributeKey.stringKey("rule"), ruleCode
            ));
        }
    }
}
```

**Pros:**
- Vendor-neutral (works with any backend)
- Unified observability (traces + metrics + logs)
- Cloud-native standard

**Cons:**
- Requires OpenTelemetry Collector
- More complex setup

---

### Option 4: CloudWatch Metrics (AWS Environments)

**Architecture:**
```
Helios Service → CloudWatch SDK → CloudWatch → CloudWatch Dashboards
```

**Implementation:**

1. Add AWS SDK dependency:
```xml
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>cloudwatch</artifactId>
    <version>2.20.0</version>
</dependency>
```

2. Create scheduled metrics publisher:
```java
@ApplicationScoped
public class CloudWatchMetricsPublisher {

    @Inject
    RuleMetricsAggregator metricsAggregator;

    private final CloudWatchClient cloudWatch = CloudWatchClient.create();

    @Scheduled(every = "1m")
    public void publishMetrics() {
        var summary = metricsAggregator.getSummary();

        var request = PutMetricDataRequest.builder()
            .namespace("Helios/RuleEngine")
            .metricData(
                MetricDatum.builder()
                    .metricName("Evaluations")
                    .value((double) summary.totalEvaluations())
                    .unit(StandardUnit.COUNT)
                    .timestamp(Instant.now())
                    .build(),
                MetricDatum.builder()
                    .metricName("CacheHitRate")
                    .value(summary.cacheHitRate() * 100)
                    .unit(StandardUnit.PERCENT)
                    .timestamp(Instant.now())
                    .build()
            )
            .build();

        cloudWatch.putMetricData(request);
    }
}
```

**Pros:**
- Native AWS integration
- No additional infrastructure
- Built-in dashboards and alarms

**Cons:**
- AWS vendor lock-in
- Can be expensive at scale
- 1-minute granularity limit

---

### Option 5: Simple HTTP Push (Any System)

**Architecture:**
```
Helios Service → Scheduled Task → HTTP POST → Your Monitoring API
```

Create a generic exporter that can push to any HTTP endpoint:

```java
@ApplicationScoped
public class HttpMetricsExporter {

    @ConfigProperty(name = "metrics.export.url")
    Optional<String> exportUrl;

    @ConfigProperty(name = "metrics.export.interval", defaultValue = "60s")
    String exportInterval;

    @Inject
    RuleMetricsAggregator metricsAggregator;

    @Scheduled(every = "{metrics.export.interval}")
    public void exportMetrics() {
        if (exportUrl.isEmpty()) return;

        var summary = metricsAggregator.getSummary();
        var hotRules = metricsAggregator.getHotRules(10);

        var payload = Map.of(
            "timestamp", Instant.now().toString(),
            "summary", summary,
            "hotRules", hotRules
        );

        // Send via HTTP client
        HttpClient.create()
            .post(exportUrl.get())
            .json(payload)
            .send();
    }
}
```

Configure in `application.properties`:
```properties
metrics.export.url=https://your-monitoring-system.com/api/metrics
metrics.export.interval=60s
```

---

## Recommended Approach

**For Development:**
- Use existing REST API + UI Dashboard ✅ (Already implemented)

**For Production:**

1. **Kubernetes/Cloud-Native**: OpenTelemetry → Prometheus/VictoriaMetrics → Grafana
2. **AWS**: CloudWatch integration
3. **On-Premise**: Prometheus + Grafana
4. **Custom**: HTTP Push to existing monitoring system

---

## Metrics to Monitor

### Core Metrics
- `helios_evaluations_total` - Total evaluation count
- `helios_matches_total` - Total matches
- `helios_match_rate` - Match rate (0.0-1.0)
- `helios_cache_hit_rate` - Cache efficiency
- `helios_throughput_events_per_minute` - Throughput

### Per-Rule Metrics
- `helios_rule_evaluations_total{rule="RULE_CODE"}` - Per-rule evaluation count
- `helios_rule_matches_total{rule="RULE_CODE"}` - Per-rule match count
- `helios_rule_latency_p99_seconds{rule="RULE_CODE"}` - P99 latency

### System Metrics (via JVM)
- `jvm_memory_used_bytes`
- `jvm_gc_collection_seconds`
- `jvm_threads_current`

---

## Alerting Examples

### Prometheus Alert Rules
```yaml
groups:
  - name: helios_alerts
    rules:
      - alert: HighMatchRate
        expr: helios_match_rate > 0.8
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "High match rate detected"

      - alert: LowCacheHitRate
        expr: helios_cache_hit_rate < 0.5
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Cache hit rate below 50%"

      - alert: SlowRuleDetected
        expr: helios_rule_latency_p99_seconds > 0.1
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "Rule {{ $labels.rule }} has P99 latency > 100ms"
```

---

## Grafana Dashboards

### Sample Dashboard JSON
See `grafana-dashboard.json` for a complete dashboard template with:
- Real-time throughput graph
- Match rate gauge
- Cache hit rate trend
- Hot rules table
- Latency heatmap
- Per-rule performance breakdown

Import via: Grafana → Dashboards → Import → Upload JSON

---

## Testing Metrics Integration

1. **Local Testing**:
```bash
# Test Prometheus format
curl http://localhost:8080/api/v1/metrics

# Test JSON format
curl http://localhost:8080/api/v1/monitoring/summary
```

2. **Load Testing**:
```bash
# Generate load to populate metrics
for i in {1..1000}; do
  curl -X POST http://localhost:8080/api/v1/evaluate \
    -H "Content-Type: application/json" \
    -d '{"eventId":"test-'$i'","fields":{"TOTAL_SPEND":15000}}'
done
```

3. **Verify Metrics**:
```bash
# Check hot rules
curl http://localhost:8080/api/v1/monitoring/hot-rules

# Check slow rules
curl http://localhost:8080/api/v1/monitoring/slow-rules
```

---

## Memory and Performance Considerations

- **In-Memory Metrics**: ~400KB for 1000 rules (current implementation)
- **Prometheus Exporter**: ~50KB additional overhead
- **Export Frequency**: Recommended 15-60 seconds
- **Cardinality Limits**: Keep per-rule metrics under 10,000 unique rule codes

---

## Migration Path

**Phase 1: Development** ✅
- Use REST API + UI Dashboard
- Manual metric inspection

**Phase 2: Staging**
- Add Prometheus exporter endpoint
- Set up local Prometheus + Grafana
- Configure basic alerts

**Phase 3: Production**
- Deploy to production monitoring stack
- Enable alerting
- Create runbooks for common issues

---

## Further Reading

- [Prometheus Best Practices](https://prometheus.io/docs/practices/)
- [OpenTelemetry Metrics](https://opentelemetry.io/docs/concepts/signals/metrics/)
- [VictoriaMetrics Documentation](https://docs.victoriametrics.com/)
- [Grafana Dashboard Best Practices](https://grafana.com/docs/grafana/latest/dashboards/build-dashboards/best-practices/)
