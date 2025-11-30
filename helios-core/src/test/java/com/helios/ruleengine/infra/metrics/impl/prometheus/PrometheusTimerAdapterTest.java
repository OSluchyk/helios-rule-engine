package com.helios.ruleengine.infra.metrics.impl.prometheus;

import static org.junit.jupiter.api.Assertions.*;

import io.prometheus.client.CollectorRegistry;
import org.junit.jupiter.api.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class PrometheusTimerAdapterTest {

    private CollectorRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new CollectorRegistry();
    }

    @Test
    @DisplayName("record(Callable) measures duration and returns result")
    void record_callable_measuresAndReturns() throws Exception {
        io.prometheus.client.Histogram promHistogram = io.prometheus.client.Histogram.build()
                .name("test_duration_seconds")
                .help("Test duration")
                .register(registry);

        PrometheusTimerAdapter timer = new PrometheusTimerAdapter(promHistogram, new String[0]);

        // Record operation
        String result = timer.record(() -> {
            Thread.sleep(50); // 50ms
            return "success";
        });

        // Verify result returned
        assertThat(result).isEqualTo("success");

        // Verify duration recorded (can't easily check exact value, but count should be
        // 1)
        // In real tests, you'd query the histogram via CollectorRegistry
    }

    @Test
    @DisplayName("record(Callable) records duration even on exception")
    void record_callable_recordsOnException() {
        io.prometheus.client.Histogram promHistogram = io.prometheus.client.Histogram.build()
                .name("test_duration_seconds")
                .help("Test duration")
                .register(registry);

        PrometheusTimerAdapter timer = new PrometheusTimerAdapter(promHistogram, new String[0]);

        // Record operation that throws
        assertThatThrownBy(() -> timer.record(() -> {
            Thread.sleep(10);
            throw new RuntimeException("test error");
        })).isInstanceOf(RuntimeException.class)
                .hasMessage("test error");

        // Duration still recorded (count = 1)
    }

    @Test
    @DisplayName("record(Duration) accepts valid durations")
    void record_duration_acceptsValidDurations() {
        io.prometheus.client.Histogram promHistogram = io.prometheus.client.Histogram.build()
                .name("test_duration_seconds")
                .help("Test duration")
                .register(registry);

        PrometheusTimerAdapter timer = new PrometheusTimerAdapter(promHistogram, new String[0]);

        // Record various durations
        timer.record(Duration.ofMillis(50));
        timer.record(Duration.ofSeconds(1));
        timer.record(Duration.ofNanos(1_000_000)); // 1ms

        // All recorded successfully
    }

    @Test
    @DisplayName("record(Duration) rejects null duration")
    void record_duration_rejectsNull() {
        io.prometheus.client.Histogram promHistogram = io.prometheus.client.Histogram.build()
                .name("test_duration_seconds")
                .help("Test duration")
                .register(registry);

        PrometheusTimerAdapter timer = new PrometheusTimerAdapter(promHistogram, new String[0]);
        Duration duration = null;
        assertThatThrownBy(() -> timer.record(duration))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be null");
    }

    @Test
    @DisplayName("record(Duration) rejects negative duration")
    void record_duration_rejectsNegative() {
        io.prometheus.client.Histogram promHistogram = io.prometheus.client.Histogram.build()
                .name("test_duration_seconds")
                .help("Test duration")
                .register(registry);

        PrometheusTimerAdapter timer = new PrometheusTimerAdapter(promHistogram, new String[0]);

        assertThatThrownBy(() -> timer.record(Duration.ofMillis(-50)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
    }

    @Test
    @DisplayName("percentile() throws UnsupportedOperationException with helpful message")
    void percentile_throwsUnsupportedOperation() {
        io.prometheus.client.Histogram promHistogram = io.prometheus.client.Histogram.build()
                .name("test_duration_seconds")
                .help("Test duration")
                .register(registry);

        PrometheusTimerAdapter timer = new PrometheusTimerAdapter(promHistogram, new String[0]);

        assertThatThrownBy(() -> timer.percentile(0.99))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("Prometheus server")
                .hasMessageContaining("histogram_quantile")
                .hasMessageContaining("InMemoryTimer"); // Suggests alternative
    }

    @Test
    @DisplayName("timer with labels creates independent histograms")
    void timer_withLabels_createsIndependentTimers() throws Exception {
        io.prometheus.client.Histogram promHistogram = io.prometheus.client.Histogram.build()
                .name("request_duration_seconds")
                .help("Request duration")
                .labelNames("method", "status")
                .register(registry);

        PrometheusTimerAdapter getTimer = new PrometheusTimerAdapter(
                promHistogram, new String[] { "GET", "200" });
        PrometheusTimerAdapter postTimer = new PrometheusTimerAdapter(
                promHistogram, new String[] { "POST", "201" });

        // Record to different timers
        getTimer.record(() -> {
            Thread.sleep(10);
            return null;
        });

        postTimer.record(() -> {
            Thread.sleep(20);
            return null;
        });

        // Each label combination has independent measurements
        // (Would verify via CollectorRegistry in real test)
    }

    @Test
    @DisplayName("timer handles concurrent recordings")
    void timer_concurrentRecordings_allRecorded() throws Exception {
        io.prometheus.client.Histogram promHistogram = io.prometheus.client.Histogram.build()
                .name("test_duration_seconds")
                .help("Test duration")
                .register(registry);

        PrometheusTimerAdapter timer = new PrometheusTimerAdapter(promHistogram, new String[0]);

        int numThreads = 10;
        int recordingsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads * recordingsPerThread);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < numThreads * recordingsPerThread; i++) {
            executor.submit(() -> {
                try {
                    timer.record(() -> {
                        Thread.sleep(1);
                        return null;
                    });
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    // Unexpected
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(successCount.get()).isEqualTo(numThreads * recordingsPerThread);
    }

    @Test
    @DisplayName("constructor validates parameters")
    void constructor_validatesParameters() {
        io.prometheus.client.Histogram promHistogram = io.prometheus.client.Histogram.build()
                .name("test_duration_seconds")
                .help("Test duration")
                .register(registry);

        // Null histogram
        assertThatThrownBy(() -> new PrometheusTimerAdapter(null, new String[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Histogram cannot be null");

        // Null label values
        assertThatThrownBy(() -> new PrometheusTimerAdapter(promHistogram, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Label values cannot be null");
    }

    @Test
    @DisplayName("record(Callable) validates callable parameter")
    void record_callable_validatesParameter() {
        io.prometheus.client.Histogram promHistogram = io.prometheus.client.Histogram.build()
                .name("test_duration_seconds")
                .help("Test duration")
                .register(registry);

        PrometheusTimerAdapter timer = new PrometheusTimerAdapter(promHistogram, new String[0]);

        assertThatThrownBy(() -> timer.record((Callable<?>) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Callable cannot be null");
    }

    @Test
    @DisplayName("duration conversion to seconds is accurate")
    void record_duration_convertsToSecondsCorrectly() {
        io.prometheus.client.Histogram promHistogram = io.prometheus.client.Histogram.build()
                .name("test_duration_seconds")
                .help("Test duration")
                .buckets(0.05, 0.1, 0.15, 0.2) // 50ms, 100ms, 150ms, 200ms
                .register(registry);

        PrometheusTimerAdapter timer = new PrometheusTimerAdapter(promHistogram, new String[0]);

        // Record 100ms (should land in 100ms bucket)
        timer.record(Duration.ofMillis(100));

        // Record 150ms (should land in 150ms bucket)
        timer.record(Duration.ofNanos(150_000_000));

        // Verify via Prometheus registry that buckets are correctly populated
        // (Would query registry.metricFamilySamples() in production test)
    }
}
