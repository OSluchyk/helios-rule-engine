package com.helios.ruleengine.metrics.impl.inmemory;


import com.helios.ruleengine.metrics.Counter;
import com.helios.ruleengine.metrics.Gauge;
import com.helios.ruleengine.metrics.MetricsRegistry;
import com.helios.ruleengine.metrics.Timer;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * In-memory metrics registry for testing.
 *
 * <p>Provides access to recorded values for assertions:
 * <pre>{@code
 * InMemoryMetricsRegistry metrics = (InMemoryMetricsRegistry) MetricsRegistry.getInstance();
 *
 * // Record some metrics
 * metrics.counter("errors").increment();
 *
 * // Assert in tests
 * assertThat(metrics.getCounterValue("errors")).isEqualTo(1L);
 * }</pre>
 */
public final class InMemoryMetricsRegistry implements MetricsRegistry {

    private final Map<String, InMemoryCounter> counters = new ConcurrentHashMap<>();
    private final Map<String, InMemoryGauge> gauges = new ConcurrentHashMap<>();
    private final Map<String, InMemoryTimer> timers = new ConcurrentHashMap<>();

    @Override
    public Counter counter(String name, String... tags) {
        return counters.computeIfAbsent(name, InMemoryCounter::new);
    }

    @Override
    public Gauge gauge(String name, String... tags) {
        return gauges.computeIfAbsent(name, InMemoryGauge::new);
    }

    @Override
    public Timer timer(String name, String... tags) {
        return timers.computeIfAbsent(name, InMemoryTimer::new);
    }

    // Test helper methods

    public long getCounterValue(String name) {
        Counter counter = counters.get(name);
        return counter != null ? counter.count() : 0L;
    }

    public double getGaugeValue(String name) {
        Gauge gauge = gauges.get(name);
        return gauge != null ? gauge.value() : 0.0;
    }

    public List<Duration> getTimerRecordings(String name) {
        InMemoryTimer timer = timers.get(name);
        return timer != null ? timer.getRecordings() : Collections.emptyList();
    }

    public void reset() {
        counters.clear();
        gauges.clear();
        timers.clear();
    }
}

