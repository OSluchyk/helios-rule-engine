package com.helios.ruleengine.metrics.impl.prometheus;


import com.helios.ruleengine.metrics.Counter;

/**
 * Adapter that bridges Helios Counter interface to Prometheus Counter.
 *
 * <p>Thread-safe wrapper around Prometheus counter. Handles label binding
 * to ensure each unique label combination gets its own counter instance.
 *
 * <h3>Label Handling:</h3>
 * <pre>{@code
 * // Create counter with labels
 * io.prometheus.client.Counter promCounter = Counter.build()
 *     .name("requests_total")
 *     .labelNames("method", "status")
 *     .register();
 *
 * // Bind to specific label values
 * Counter getCounter = new PrometheusCounterAdapter(promCounter, new String[]{"GET", "200"});
 * Counter postCounter = new PrometheusCounterAdapter(promCounter, new String[]{"POST", "201"});
 *
 * getCounter.increment();   // Increments requests_total{method="GET",status="200"}
 * postCounter.increment();  // Increments requests_total{method="POST",status="201"}
 * }</pre>
 *
 * @author Helios Platform Team
 * @since 2.0.0
 */
final class PrometheusCounterAdapter implements Counter {

    /**
     * The Prometheus counter child bound to specific label values.
     * This is the actual counter instance that stores the value.
     */
    private final io.prometheus.client.Counter.Child counter;

    /**
     * Creates an adapter for a Prometheus counter with specific label values.
     *
     * @param counter the Prometheus counter (parent, not yet bound to labels)
     * @param labelValues the values for each label (must match labelNames order)
     *
     * @throws IllegalArgumentException if labelValues length doesn't match labelNames
     */
    PrometheusCounterAdapter(io.prometheus.client.Counter counter, String[] labelValues) {
        if (labelValues == null) {
            throw new IllegalArgumentException("Label values cannot be null");
        }

        // Bind counter to specific label values
        // If no labels, labels() returns the default child
        // If labels exist, labels(values...) returns a child for those specific values
        this.counter = labelValues.length > 0
                ? counter.labels(labelValues)
                : counter.labels();  // No labels = default child
    }

    /**
     * Increments the counter by 1.
     *
     * <p>Thread-safe operation. Multiple threads can increment concurrently
     * without external synchronization.
     */
    @Override
    public void increment() {
        counter.inc();
    }

    /**
     * Increments the counter by the specified amount.
     *
     * <p>Thread-safe operation.
     *
     * @param amount the amount to increment (must be non-negative)
     * @throws IllegalArgumentException if amount is negative
     */
    @Override
    public void increment(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("Counter increment amount cannot be negative: " + amount);
        }
        counter.inc(amount);
    }

    /**
     * Gets the current counter value.
     *
     * <p>This is a read of the current value and may not reflect concurrent
     * increments happening at the exact same moment.
     *
     * @return current counter value
     */
    @Override
    public long count() {
        // Prometheus stores as double, but counters are always whole numbers
        return (long) counter.get();
    }

    /**
     * Gets the underlying Prometheus counter child.
     * Exposed for testing purposes.
     *
     * @return Prometheus counter child
     */
    io.prometheus.client.Counter.Child getPrometheusCounter() {
        return counter;
    }

    @Override
    public String toString() {
        return String.format("PrometheusCounterAdapter{value=%d}", count());
    }
}
