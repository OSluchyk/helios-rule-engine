package com.helios.ruleengine.metrics.impl.prometheus;


import io.prometheus.client.CollectorRegistry;
import org.junit.jupiter.api.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class PrometheusCounterAdapterTest {

    private CollectorRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new CollectorRegistry();
    }

    @Test
    void increment_increasesValueByOne() {
        io.prometheus.client.Counter promCounter = io.prometheus.client.Counter.build()
                .name("test_counter")
                .help("Test counter")
                .register(registry);

        PrometheusCounterAdapter counter = new PrometheusCounterAdapter(promCounter, new String[0]);

        counter.increment();
        assertThat(counter.count()).isEqualTo(1L);

        counter.increment();
        assertThat(counter.count()).isEqualTo(2L);
    }

    @Test
    void increment_withAmount_increasesValueByAmount() {
        io.prometheus.client.Counter promCounter = io.prometheus.client.Counter.build()
                .name("test_counter")
                .help("Test counter")
                .register(registry);

        PrometheusCounterAdapter counter = new PrometheusCounterAdapter(promCounter, new String[0]);

        counter.increment(10);
        assertThat(counter.count()).isEqualTo(10L);

        counter.increment(5);
        assertThat(counter.count()).isEqualTo(15L);
    }

    @Test
    void increment_negativeAmount_throwsException() {
        io.prometheus.client.Counter promCounter = io.prometheus.client.Counter.build()
                .name("test_counter")
                .help("Test counter")
                .register(registry);

        PrometheusCounterAdapter counter = new PrometheusCounterAdapter(promCounter, new String[0]);

        assertThatThrownBy(() -> counter.increment(-5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
    }

    @Test
    void counter_withLabels_createsIndependentCounters() {
        io.prometheus.client.Counter promCounter = io.prometheus.client.Counter.build()
                .name("requests_total")
                .help("Total requests")
                .labelNames("method", "status")
                .register(registry);

        PrometheusCounterAdapter getOk = new PrometheusCounterAdapter(
                promCounter, new String[]{"GET", "200"});
        PrometheusCounterAdapter postError = new PrometheusCounterAdapter(
                promCounter, new String[]{"POST", "500"});

        getOk.increment();
        getOk.increment();
        postError.increment();

        assertThat(getOk.count()).isEqualTo(2L);
        assertThat(postError.count()).isEqualTo(1L);
    }

    @Test
    void counter_concurrentIncrements_allCountedCorrectly() throws Exception {
        io.prometheus.client.Counter promCounter = io.prometheus.client.Counter.build()
                .name("test_counter")
                .help("Test counter")
                .register(registry);

        PrometheusCounterAdapter counter = new PrometheusCounterAdapter(promCounter, new String[0]);

        int numThreads = 10;
        int incrementsPerThread = 1000;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads * incrementsPerThread);

        for (int i = 0; i < numThreads; i++) {
            executor.submit(() -> {
                for (int j = 0; j < incrementsPerThread; j++) {
                    counter.increment();
                    latch.countDown();
                }
            });
        }

        latch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(counter.count()).isEqualTo(numThreads * incrementsPerThread);
    }
}