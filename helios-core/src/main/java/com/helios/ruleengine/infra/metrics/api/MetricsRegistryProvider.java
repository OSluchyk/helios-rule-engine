package com.helios.ruleengine.infra.metrics.api;

import com.helios.ruleengine.infra.metrics.MetricsRegistry;

/**
 * Service Provider Interface for {@link MetricsRegistry} implementations.
 *
 * <p>Implementations must:
 * <ul>
 *   <li>Have a public no-arg constructor
 *   <li>Be thread-safe
 *   <li>Register themselves via ServiceLoader
 * </ul>
 *
 * <h3>Example Implementation:</h3>
 * <pre>{@code
 * public class PrometheusMetricsRegistryProvider implements MetricsRegistryProvider {
 *     @Override
 *     public MetricsRegistry create() {
 *         return new PrometheusMetricsRegistry();
 *     }
 *
 *     @Override
 *     public int priority() {
 *         return 100;  // Higher = preferred
 *     }
 * }
 * }</pre>
 *
 * @since 2.0.0
 */
public interface MetricsRegistryProvider {

    /**
     * Creates a new MetricsRegistry instance.
     *
     * @return registry instance (must be thread-safe)
     */
    MetricsRegistry create();

    /**
     * Provider priority for selection.
     * Higher values are preferred when multiple providers exist.
     *
     * @return priority (default: 0)
     */
    default int priority() {
        return 0;
    }

    /**
     * Provider name for logging.
     *
     * @return human-readable name
     */
    default String name() {
        return getClass().getSimpleName();
    }
}
