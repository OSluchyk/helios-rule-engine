package com.helios.ruleengine.infra.metrics.impl.inmemory;


import com.helios.ruleengine.infra.metrics.MetricsRegistry;
import com.helios.ruleengine.infra.metrics.api.MetricsRegistryProvider;

/**
 * In-memory metrics provider for testing.
 *
 * <p>To enable in tests, create:
 * <pre>
 * src/test/resources/META-INF/services/com.helios.rules.metrics.spi.MetricsRegistryProvider
 *
 * Contents:
 * com.helios.rules.metrics.testing.InMemoryMetricsRegistryProvider
 * </pre>
 */
public final class InMemoryMetricsRegistryProvider implements MetricsRegistryProvider {

    @Override
    public MetricsRegistry create() {
        return new InMemoryMetricsRegistry();
    }

    @Override
    public int priority() {
        return 1000;  // Highest priority in test environment
    }

    @Override
    public String name() {
        return "InMemory (Test)";
    }
}
