package com.helios.ruleengine.metrics.impl.prometheus;


import com.helios.ruleengine.metrics.MetricsRegistry;
import com.helios.ruleengine.metrics.api.MetricsRegistryProvider;

/**
 * Prometheus-backed metrics provider.
 *
 * <p>Registers itself via ServiceLoader. To enable, add this file:
 * <pre>
 * src/main/resources/META-INF/services/com.helios.rules.metrics.spi.MetricsRegistryProvider
 *
 * Contents:
 * com.helios.rules.metrics.impl.prometheus.PrometheusMetricsRegistryProvider
 * </pre>
 */
public final class PrometheusMetricsRegistryProvider implements MetricsRegistryProvider {

    @Override
    public MetricsRegistry create() {
        return new PrometheusMetricsRegistry();
    }

    @Override
    public int priority() {
        return 100;  // Prefer Prometheus in production
    }

    @Override
    public String name() {
        return "Prometheus";
    }
}