package com.helios.ruleengine.metrics;

import com.helios.ruleengine.metrics.internal.MetricsRegistryHolder;

import java.time.Duration;
import java.util.concurrent.Callable;

/**
 * Framework-agnostic metrics registry.
 *
 * <p>This is the primary entry point for recording application metrics.
 * Implementations are discovered via {@link java.util.ServiceLoader}.
 *
 * <h3>Usage Example:</h3>
 * <pre>{@code
 * MetricsRegistry metrics = MetricsRegistry.getInstance();
 * Counter errors = metrics.counter("compilation_errors");
 * errors.increment();
 * }</pre>
 *
 * @since 2.0.0
 */
public interface MetricsRegistry {

    /**
     * Creates or retrieves a counter metric.
     *
     * @param name metric name (lowercase, underscores only)
     * @param tags optional key-value pairs for labels
     * @return thread-safe counter instance
     */
    Counter counter(String name, String... tags);

    /**
     * Creates or retrieves a gauge metric.
     *
     * @param name metric name
     * @param tags optional key-value pairs
     * @return thread-safe gauge instance
     */
    Gauge gauge(String name, String... tags);

    /**
     * Creates or retrieves a timer histogram.
     *
     * @param name metric name
     * @param tags optional key-value pairs
     * @return thread-safe timer instance
     */
    Timer timer(String name, String... tags);

    /**
     * Gets the singleton registry instance.
     *
     * <p>Implementation is discovered via ServiceLoader.
     * Falls back to no-op if no provider found.
     *
     * @return global metrics registry
     */
    static MetricsRegistry getInstance() {
        return MetricsRegistryHolder.INSTANCE;
    }
}