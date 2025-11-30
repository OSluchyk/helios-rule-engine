package com.helios.ruleengine.infra.metrics.impl.prometheus;

import static org.junit.jupiter.api.Assertions.*;
import io.prometheus.client.CollectorRegistry;
import org.junit.jupiter.api.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class PrometheusGaugeAdapterTest {

    private CollectorRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new CollectorRegistry();
    }

    @Test
    void set_updatesValue() {
        io.prometheus.client.Gauge promGauge = io.prometheus.client.Gauge.build()
                .name("test_gauge")
                .help("Test gauge")
                .register(registry);

        PrometheusGaugeAdapter gauge = new PrometheusGaugeAdapter(promGauge, new String[0]);

        gauge.set(42.5);
        assertThat(gauge.value()).isEqualTo(42.5);

        gauge.set(100.0);
        assertThat(gauge.value()).isEqualTo(100.0);
    }

    @Test
    void increment_increasesValue() {
        io.prometheus.client.Gauge promGauge = io.prometheus.client.Gauge.build()
                .name("test_gauge")
                .help("Test gauge")
                .register(registry);

        PrometheusGaugeAdapter gauge = new PrometheusGaugeAdapter(promGauge, new String[0]);

        gauge.set(10.0);
        gauge.increment();
        assertThat(gauge.value()).isEqualTo(11.0);

        gauge.increment(5.0);
        assertThat(gauge.value()).isEqualTo(16.0);
    }

    @Test
    void decrement_decreasesValue() {
        io.prometheus.client.Gauge promGauge = io.prometheus.client.Gauge.build()
                .name("test_gauge")
                .help("Test gauge")
                .register(registry);

        PrometheusGaugeAdapter gauge = new PrometheusGaugeAdapter(promGauge, new String[0]);

        gauge.set(10.0);
        gauge.decrement();
        assertThat(gauge.value()).isEqualTo(9.0);

        gauge.decrement(3.0);
        assertThat(gauge.value()).isEqualTo(6.0);
    }

    @Test
    void gauge_canBeNegative() {
        io.prometheus.client.Gauge promGauge = io.prometheus.client.Gauge.build()
                .name("test_gauge")
                .help("Test gauge")
                .register(registry);

        PrometheusGaugeAdapter gauge = new PrometheusGaugeAdapter(promGauge, new String[0]);

        gauge.set(-42.5);
        assertThat(gauge.value()).isEqualTo(-42.5);
    }

    @Test
    void gauge_withLabels_createsIndependentGauges() {
        io.prometheus.client.Gauge promGauge = io.prometheus.client.Gauge.build()
                .name("cache_size")
                .help("Cache size")
                .labelNames("cache_name")
                .register(registry);

        PrometheusGaugeAdapter ruleCache = new PrometheusGaugeAdapter(
                promGauge, new String[] { "rule_cache" });
        PrometheusGaugeAdapter conditionCache = new PrometheusGaugeAdapter(
                promGauge, new String[] { "condition_cache" });

        ruleCache.set(1000.0);
        conditionCache.set(5000.0);

        assertThat(ruleCache.value()).isEqualTo(1000.0);
        assertThat(conditionCache.value()).isEqualTo(5000.0);
    }

    @Test
    void setToCurrentTime_setsReasonableTimestamp() {
        io.prometheus.client.Gauge promGauge = io.prometheus.client.Gauge.build()
                .name("last_update_time")
                .help("Last update timestamp")
                .register(registry);

        PrometheusGaugeAdapter gauge = new PrometheusGaugeAdapter(promGauge, new String[0]);

        long beforeSeconds = System.currentTimeMillis() / 1000;
        gauge.setToCurrentTime();
        long afterSeconds = System.currentTimeMillis() / 1000;

        double timestamp = gauge.value();
        assertThat(timestamp)
                .isGreaterThanOrEqualTo(beforeSeconds)
                .isLessThanOrEqualTo(afterSeconds + 1); // +1 for rounding
    }

    @Test
    void gauge_concurrentUpdates_lastWriteWins() throws Exception {
        io.prometheus.client.Gauge promGauge = io.prometheus.client.Gauge.build()
                .name("test_gauge")
                .help("Test gauge")
                .register(registry);

        PrometheusGaugeAdapter gauge = new PrometheusGaugeAdapter(promGauge, new String[0]);

        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(100);

        for (int i = 0; i < 100; i++) {
            final double value = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    gauge.set(value);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // Release all threads
        endLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        // Should have some value between 0 and 99
        assertThat(gauge.value())
                .isGreaterThanOrEqualTo(0.0)
                .isLessThanOrEqualTo(99.0);
    }
}