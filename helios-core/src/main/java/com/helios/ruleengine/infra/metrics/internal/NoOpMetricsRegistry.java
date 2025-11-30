package com.helios.ruleengine.infra.metrics.internal;


import com.helios.ruleengine.infra.metrics.Counter;
import com.helios.ruleengine.infra.metrics.Gauge;
import com.helios.ruleengine.infra.metrics.MetricsRegistry;
import com.helios.ruleengine.infra.metrics.Timer;

import java.time.Duration;
import java.util.concurrent.Callable;

/**
 * No-op implementation (zero overhead).
 * Used as fallback when no provider configured.
 */
final class NoOpMetricsRegistry implements MetricsRegistry {

    private static final Counter NO_OP_COUNTER = new NoOpCounter();
    private static final Gauge NO_OP_GAUGE = new NoOpGauge();
    private static final Timer NO_OP_TIMER = new NoOpTimer();

    @Override
    public Counter counter(String name, String... tags) {
        return NO_OP_COUNTER;
    }

    @Override
    public Gauge gauge(String name, String... tags) {
        return NO_OP_GAUGE;
    }

    @Override
    public Timer timer(String name, String... tags) {
        return NO_OP_TIMER;
    }

    private static final class NoOpCounter implements Counter {
        public void increment() {}
        public void increment(long amount) {}
        public long count() { return 0L; }
    }

    private static final class NoOpGauge implements Gauge {
        public void set(double value) {}
        public double value() { return 0.0; }
    }

    private static final class NoOpTimer implements Timer {
        public <T> T record(Callable<T> callable) throws Exception {
            return callable.call();
        }
        public void record(Duration duration) {}
        public Duration percentile(double p) { return Duration.ZERO; }
    }
}