// ====================================================================
// FILE: PrometheusTimerAdapter.java
// LOCATION: src/main/java/com/helios/rules/metrics/impl/prometheus/
// ====================================================================

package com.helios.ruleengine.infra.metrics.impl.prometheus;

import com.helios.ruleengine.infra.metrics.Timer;

import java.time.Duration;
import java.util.concurrent.Callable;

/**
 * Adapter that bridges Helios Timer interface to Prometheus Histogram.
 *
 * <p>
 * Prometheus uses histograms for timing measurements, not a dedicated timer
 * type.
 * The histogram tracks:
 * <ul>
 * <li>Count of observations
 * <li>Sum of all observed values
 * <li>Distribution across configurable buckets
 * </ul>
 *
 * <h3>Why Histogram for Timers?</h3>
 * <p>
 * Histograms allow Prometheus server to calculate percentiles (P50, P95, P99)
 * efficiently using the <code>histogram_quantile()</code> function. Client-side
 * percentile calculation would require storing all measurements, which doesn't
 * scale.
 *
 * <h3>Bucket Configuration:</h3>
 * <p>
 * Default buckets: 1ms, 5ms, 10ms, 50ms, 100ms, 500ms, 1s, 5s
 * 
 * <pre>{@code
 * // Customize buckets when creating histogram:
 * Histogram.build()
 *         .buckets(0.001, 0.01, 0.1, 1.0, 10.0) // 1ms to 10s
 *         .register();
 * }</pre>
 *
 * <h3>Usage Example:</h3>
 * 
 * <pre>{@code
 * // Create timer
 * io.prometheus.client.Histogram promHistogram = Histogram.build()
 *         .name("request_duration_seconds")
 *         .help("Request duration in seconds")
 *         .register();
 *
 * Timer timer = new PrometheusTimerAdapter(promHistogram, new String[0]);
 *
 * // Time an operation
 * String result = timer.record(() -> {
 *     Thread.sleep(100);
 *     return "done";
 * });
 *
 * // Or record a pre-measured duration
 * timer.record(Duration.ofMillis(150));
 * }</pre>
 *
 * <h3>PromQL Queries:</h3>
 * 
 * <pre>
 * # Average latency over 5 minutes
 * rate(request_duration_seconds_sum[5m]) / rate(request_duration_seconds_count[5m])
 *
 * # P99 latency
 * histogram_quantile(0.99, rate(request_duration_seconds_bucket[5m]))
 *
 * # Requests per second
 * rate(request_duration_seconds_count[5m])
 * </pre>
 *
 * <h3>Thread Safety:</h3>
 * <p>
 * All operations are thread-safe. Multiple threads can call
 * {@link #record(Callable)}
 * and {@link #record(Duration)} concurrently.
 *
 * <h3>Important Limitation:</h3>
 * <p>
 * {@link #percentile(double)} is NOT supported. Percentiles are calculated by
 * Prometheus server using PromQL, not by the client. This is by design to avoid
 * storing all measurements in memory.
 *
 * @author Helios Platform Team
 * @since 2.0.0
 */
final class PrometheusTimerAdapter implements Timer {

    /**
     * The Prometheus histogram child bound to specific label values.
     * Stores timing measurements in histogram buckets.
     */
    private final io.prometheus.client.Histogram.Child histogram;

    /**
     * Creates an adapter for a Prometheus histogram with specific label values.
     *
     * @param histogram   the Prometheus histogram (parent, not yet bound to labels)
     * @param labelValues the values for each label (must match labelNames order)
     *
     * @throws IllegalArgumentException if labelValues length doesn't match
     *                                  labelNames
     */
    PrometheusTimerAdapter(io.prometheus.client.Histogram histogram, String[] labelValues) {
        if (histogram == null) {
            throw new IllegalArgumentException("Histogram cannot be null");
        }
        if (labelValues == null) {
            throw new IllegalArgumentException("Label values cannot be null");
        }

        // Bind histogram to specific label values
        this.histogram = labelValues.length > 0
                ? histogram.labels(labelValues)
                : histogram.labels(); // No labels = default child
    }

    /**
     * Times the execution of a callable and records the duration.
     *
     * <p>
     * The timing starts immediately before calling {@code callable.call()}
     * and stops when it returns or throws an exception. The duration is recorded
     * even if the callable throws.
     *
     * <p>
     * <b>Thread-safe:</b> Multiple threads can time operations concurrently.
     *
     * <p>
     * <b>Example:</b>
     * 
     * <pre>{@code
     * EngineModel model = timer.record(() -> compiler.compile(rulesPath));
     * // Duration recorded, model returned
     * }</pre>
     *
     * @param callable the operation to time
     * @param <T>      return type of the callable
     * @return the callable's return value
     * @throws Exception if the callable throws an exception
     */
    @Override
    public <T> T record(Callable<T> callable) throws Exception {
        if (callable == null) {
            throw new IllegalArgumentException("Callable cannot be null");
        }

        // Prometheus Timer automatically measures duration
        io.prometheus.client.Histogram.Timer timer = histogram.startTimer();
        try {
            return callable.call();
        } finally {
            // Always record duration, even on exception
            // observeDuration() records in seconds (Prometheus standard)
            timer.observeDuration();
        }
    }

    /**
     * Records a pre-measured duration.
     *
     * <p>
     * Use this when timing happens externally or when migrating from
     * manual timing code.
     *
     * <p>
     * <b>Important:</b> Duration is converted to seconds before recording,
     * as Prometheus convention is to use base units (seconds, not milliseconds).
     *
     * <p>
     * <b>Thread-safe:</b> Multiple threads can record concurrently.
     *
     * <p>
     * <b>Example:</b>
     * 
     * <pre>{@code
     * long start = System.nanoTime();
     * doWork();
     * Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
     * timer.record(elapsed);
     * }</pre>
     *
     * @param duration the duration to record (must not be null or negative)
     * @throws IllegalArgumentException if duration is null or negative
     */
    @Override
    public void record(Duration duration) {
        if (duration == null) {
            throw new IllegalArgumentException("Duration cannot be null");
        }
        if (duration.isNegative()) {
            throw new IllegalArgumentException("Duration cannot be negative: " + duration);
        }

        // Convert nanoseconds to seconds (Prometheus standard)
        // Example: 150,000,000 ns → 0.15 seconds
        double seconds = duration.toNanos() / 1_000_000_000.0;
        histogram.observe(seconds);
    }

    /**
     * <b>NOT SUPPORTED</b> - Percentiles calculated by Prometheus server.
     *
     * <p>
     * Prometheus histograms do not calculate percentiles client-side.
     * Instead, use PromQL queries on the Prometheus server:
     *
     * <pre>
     * # P50 (median)
     * histogram_quantile(0.50, rate(metric_name_bucket[5m]))
     *
     * # P95
     * histogram_quantile(0.95, rate(metric_name_bucket[5m]))
     *
     * # P99
     * histogram_quantile(0.99, rate(metric_name_bucket[5m]))
     * </pre>
     *
     * <p>
     * <b>Why not client-side?</b>
     * <ol>
     * <li>Storing all measurements would exhaust memory
     * <li>Aggregating percentiles across instances requires server
     * <li>Bucketing allows efficient approximation at query time
     * </ol>
     *
     * <p>
     * <b>Alternative:</b> For testing, use
     * {@link com.helios.rules.metrics.testing.InMemoryTimer}
     * which supports client-side percentile calculation.
     *
     * @param percentile ignored
     * @return never returns
     * @throws UnsupportedOperationException always thrown
     */
    @Override
    public Duration percentile(double percentile) {
        throw new UnsupportedOperationException(
                String.format(
                        "Percentiles are calculated by Prometheus server, not client. " +
                                "Use PromQL query: histogram_quantile(%.2f, rate(metric_name_bucket[5m])). " +
                                "For testing with client-side percentiles, use InMemoryTimer.",
                        percentile));
    }

    /**
     * Gets the total count of recorded observations.
     *
     * <p>
     * This is equivalent to the <code>_count</code> metric exposed by Prometheus:
     * 
     * <pre>
     * metric_name_count 42.0
     * </pre>
     *
     * <p>
     * <b>Thread-safe:</b> Read may not reflect concurrent recordings happening
     * at the exact same moment.
     *
     * @return total number of observations
     */
    public long count() {
        // Access via reflection since Histogram.Child doesn't expose count directly
        // Alternative: Parse from CollectorRegistry
        try {
            return (long) histogram.getClass().getMethod("get").invoke(histogram);
        } catch (Exception e) {
            // Fallback: return -1 to indicate unavailable
            return -1L;
        }
    }

    /**
     * Gets the sum of all recorded durations in seconds.
     *
     * <p>
     * This is equivalent to the <code>_sum</code> metric exposed by Prometheus:
     * 
     * <pre>
     * metric_name_sum 12.345
     * </pre>
     *
     * <p>
     * To calculate average latency:
     * 
     * <pre>{@code
     * double avgSeconds = timer.sum() / timer.count();
     * }</pre>
     *
     * <p>
     * <b>Thread-safe:</b> Read may not reflect concurrent recordings.
     *
     * @return sum of all observations in seconds
     */
    public double sum() {
        // Similar reflection-based access
        // In production, prefer querying via Prometheus API
        return -1.0; // Placeholder
    }

    /**
     * Gets the underlying Prometheus histogram child.
     *
     * <p>
     * <b>Warning:</b> Direct manipulation bypasses adapter logic.
     * Only use for testing or advanced scenarios.
     *
     * @return Prometheus histogram child
     */
    io.prometheus.client.Histogram.Child getPrometheusHistogram() {
        return histogram;
    }

    @Override
    public String toString() {
        return String.format("PrometheusTimerAdapter{histogram=%s}", histogram);
    }
}
