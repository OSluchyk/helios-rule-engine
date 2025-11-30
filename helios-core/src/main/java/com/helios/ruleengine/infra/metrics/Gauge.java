package com.helios.ruleengine.infra.metrics;


/**
 * Instantaneous value metric.
 * Thread-safe.
 */
public interface Gauge {
    void set(double value);
    double value();
}
