package com.helios.ruleengine.metrics.impl.prometheus;


import com.helios.ruleengine.metrics.Gauge;

/**
 * Adapter that bridges Helios Gauge interface to Prometheus Gauge.
 *
 * <p>Thread-safe wrapper around Prometheus gauge. Unlike counters, gauges
 * can go up or down, making them suitable for metrics like:
 * <ul>
 *   <li>Cache size
 *   <li>Active connections
 *   <li>Memory usage
 *   <li>Queue depth
 *   <li>Temperature, CPU %, etc.
 * </ul>
 *
 * <h3>Usage Example:</h3>
 * <pre>{@code
 * // Create gauge
 * io.prometheus.client.Gauge promGauge = Gauge.build()
 *     .name("cache_size")
 *     .help("Current cache size")
 *     .register();
 *
 * Gauge gauge = new PrometheusGaugeAdapter(promGauge, new String[0]);
 *
 * // Update gauge value
 * gauge.set(1500.0);  // Cache has 1500 entries
 *
 * // Later...
 * gauge.set(1450.0);  // Cache shrunk to 1450 entries
 * }</pre>
 *
 * <h3>Thread Safety:</h3>
 * <p>All operations are thread-safe. Multiple threads can call {@link #set(double)}
 * and {@link #value()} concurrently. The last write wins (no atomic increment).
 *
 * @author Helios Platform Team
 * @since 2.0.0
 */
final class PrometheusGaugeAdapter implements Gauge {

    /**
     * The Prometheus gauge child bound to specific label values.
     * Stores the current gauge value.
     */
    private final io.prometheus.client.Gauge.Child gauge;

    /**
     * Creates an adapter for a Prometheus gauge with specific label values.
     *
     * @param gauge the Prometheus gauge (parent, not yet bound to labels)
     * @param labelValues the values for each label (must match labelNames order)
     *
     * @throws IllegalArgumentException if labelValues length doesn't match labelNames
     */
    PrometheusGaugeAdapter(io.prometheus.client.Gauge gauge, String[] labelValues) {
        if (labelValues == null) {
            throw new IllegalArgumentException("Label values cannot be null");
        }

        // Bind gauge to specific label values
        this.gauge = labelValues.length > 0
                ? gauge.labels(labelValues)
                : gauge.labels();  // No labels = default child
    }

    /**
     * Sets the gauge to the specified value.
     *
     * <p>This operation is atomic within Prometheus. If multiple threads
     * call set() concurrently, the last write wins. There is no "increment"
     * semantic - each set() completely replaces the previous value.
     *
     * <p>Thread-safe operation.
     *
     * @param value the new gauge value (can be any double, including negative)
     */
    @Override
    public void set(double value) {
        gauge.set(value);
    }

    /**
     * Gets the current gauge value.
     *
     * <p>This read may not reflect a concurrent {@link #set(double)} happening
     * at the exact same moment, but will eventually see all writes.
     *
     * <p>Thread-safe operation.
     *
     * @return current gauge value
     */
    @Override
    public double value() {
        return gauge.get();
    }

    /**
     * Increments the gauge by 1.
     *
     * <p>Convenience method equivalent to incrementing a counter, but for gauges.
     * Note that unlike counters, gauges can also be decremented.
     *
     * <p>Thread-safe operation.
     */
    public void increment() {
        gauge.inc();
    }

    /**
     * Increments the gauge by the specified amount.
     *
     * <p>Thread-safe operation.
     *
     * @param amount the amount to increment (can be negative to decrement)
     */
    public void increment(double amount) {
        gauge.inc(amount);
    }

    /**
     * Decrements the gauge by 1.
     *
     * <p>Convenience method for decrementing.
     *
     * <p>Thread-safe operation.
     */
    public void decrement() {
        gauge.dec();
    }

    /**
     * Decrements the gauge by the specified amount.
     *
     * <p>Thread-safe operation.
     *
     * @param amount the amount to decrement (must be non-negative)
     */
    public void decrement(double amount) {
        gauge.dec(amount);
    }

    /**
     * Sets the gauge to current Unix timestamp in seconds.
     *
     * <p>Useful for tracking "last update time" metrics:
     * <pre>{@code
     * Gauge lastSync = metrics.gauge("last_sync_time");
     * // ... perform sync ...
     * ((PrometheusGaugeAdapter) lastSync).setToCurrentTime();
     * }</pre>
     *
     * <p>Thread-safe operation.
     */
    public void setToCurrentTime() {
        gauge.setToCurrentTime();
    }

    /**
     * Gets the underlying Prometheus gauge child.
     * Exposed for testing purposes.
     *
     * @return Prometheus gauge child
     */
    io.prometheus.client.Gauge.Child getPrometheusGauge() {
        return gauge;
    }

    @Override
    public String toString() {
        return String.format("PrometheusGaugeAdapter{value=%.2f}", value());
    }
}
