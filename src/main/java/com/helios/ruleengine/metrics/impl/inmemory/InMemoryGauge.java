package com.helios.ruleengine.metrics.impl.inmemory;


import com.helios.ruleengine.metrics.Gauge;

import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory implementation of {@link Gauge} for testing.
 * Thread-safe implementation using AtomicReference.
 */
final class InMemoryGauge implements Gauge {

    private final String name;
    private final AtomicReference<Double> value;

    InMemoryGauge(String name) {
        this.name = name;
        this.value = new AtomicReference<>(0.0);
    }

    @Override
    public void set(double newValue) {
        value.set(newValue);
    }

    @Override
    public double value() {
        return value.get();
    }

    String getName() {
        return name;
    }

    void reset() {
        value.set(0.0);
    }

    @Override
    public String toString() {
        return String.format("InMemoryGauge{name='%s', value=%.2f}", name, value());
    }
}
