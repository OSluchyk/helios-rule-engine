package com.helios.ruleengine.infra.metrics;


/**
 * Monotonically increasing counter.
 * Thread-safe.
 */
public interface Counter {
    void increment();
    void increment(long amount);
    long count();
}

