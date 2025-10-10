package com.helios.ruleengine.metrics;


/**
 * Monotonically increasing counter.
 * Thread-safe.
 */
public interface Counter {
    void increment();
    void increment(long amount);
    long count();
}

