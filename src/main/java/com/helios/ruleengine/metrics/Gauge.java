package com.helios.ruleengine.metrics;


/**
 * Instantaneous value metric.
 * Thread-safe.
 */
public interface Gauge {
    void set(double value);
    double value();
}
