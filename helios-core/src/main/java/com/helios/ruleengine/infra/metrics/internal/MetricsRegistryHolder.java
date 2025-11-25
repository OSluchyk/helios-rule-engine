package com.helios.ruleengine.infra.metrics.internal;


import com.helios.ruleengine.infra.metrics.MetricsRegistry;
import com.helios.ruleengine.infra.metrics.api.MetricsRegistryProvider;

import java.util.Comparator;
import java.util.ServiceLoader;
import java.util.stream.StreamSupport;

/**
 * Lazy holder for singleton MetricsRegistry.
 * Uses ServiceLoader for discovery.
 *
 * <p><b>INTERNAL USE ONLY</b> - API may change without notice.
 */
public final class MetricsRegistryHolder {

    public static final MetricsRegistry INSTANCE;

    static {
        ServiceLoader<MetricsRegistryProvider> loader =
                ServiceLoader.load(MetricsRegistryProvider.class);

        // Select highest-priority provider
        MetricsRegistryProvider provider = StreamSupport.stream(
                        loader.spliterator(), false)
                .max(Comparator.comparingInt(MetricsRegistryProvider::priority))
                .orElse(null);

        if (provider != null) {
            INSTANCE = provider.create();
            System.out.printf("[Metrics] Using provider: %s (priority: %d)%n",
                    provider.name(), provider.priority());
        } else {
            INSTANCE = new NoOpMetricsRegistry();
            System.out.println("[Metrics] No provider found, using no-op implementation");
        }
    }

    private MetricsRegistryHolder() {
        throw new AssertionError("No instances");
    }
}

