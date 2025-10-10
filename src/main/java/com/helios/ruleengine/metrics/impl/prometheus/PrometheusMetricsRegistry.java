package com.helios.ruleengine.metrics.impl.prometheus;


import com.helios.ruleengine.metrics.Counter;
import com.helios.ruleengine.metrics.Gauge;
import com.helios.ruleengine.metrics.MetricsRegistry;
import com.helios.ruleengine.metrics.Timer;
import io.prometheus.client.CollectorRegistry;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prometheus implementation of MetricsRegistry.
 * Thread-safe.
 */
public final class PrometheusMetricsRegistry implements MetricsRegistry {

    private final CollectorRegistry registry;
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private final Map<String, Gauge> gauges = new ConcurrentHashMap<>();
    private final Map<String, Timer> timers = new ConcurrentHashMap<>();

    public PrometheusMetricsRegistry() {
        this(CollectorRegistry.defaultRegistry);
    }

    public PrometheusMetricsRegistry(CollectorRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Counter counter(String name, String... tags) {
        return counters.computeIfAbsent(name, n -> {
            io.prometheus.client.Counter promCounter =
                    io.prometheus.client.Counter.build()
                            .name(sanitizeName(n))
                            .help("Auto-generated counter for " + n)
                            .labelNames(extractLabelNames(tags))
                            .register(registry);

            return new PrometheusCounterAdapter(promCounter, extractLabelValues(tags));
        });
    }

    @Override
    public Gauge gauge(String name, String... tags) {
        return gauges.computeIfAbsent(name, n -> {
            io.prometheus.client.Gauge promGauge =
                    io.prometheus.client.Gauge.build()
                            .name(sanitizeName(n))
                            .help("Auto-generated gauge for " + n)
                            .labelNames(extractLabelNames(tags))
                            .register(registry);

            return new PrometheusGaugeAdapter(promGauge, extractLabelValues(tags));
        });
    }

    @Override
    public Timer timer(String name, String... tags) {
        return timers.computeIfAbsent(name, n -> {
            io.prometheus.client.Histogram promHistogram =
                    io.prometheus.client.Histogram.build()
                            .name(sanitizeName(n) + "_seconds")
                            .help("Auto-generated timer for " + n)
                            .buckets(0.001, 0.005, 0.01, 0.05, 0.1, 0.5, 1.0, 5.0)
                            .labelNames(extractLabelNames(tags))
                            .register(registry);

            return new PrometheusTimerAdapter(promHistogram, extractLabelValues(tags));
        });
    }

    private String sanitizeName(String name) {
        return name.toLowerCase()
                .replaceAll("[^a-z0-9_:]", "_")
                .replaceAll("_{2,}", "_");
    }

    private String[] extractLabelNames(String[] tags) {
        String[] labels = new String[tags.length / 2];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = tags[i * 2];
        }
        return labels;
    }

    private String[] extractLabelValues(String[] tags) {
        String[] values = new String[tags.length / 2];
        for (int i = 0; i < values.length; i++) {
            values[i] = tags[i * 2 + 1];
        }
        return values;
    }
}