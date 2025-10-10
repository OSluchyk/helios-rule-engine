package com.helios.ruleengine.metrics;

import java.time.Duration;
import java.util.concurrent.Callable;

/**
 * Latency histogram with percentile tracking.
 * Thread-safe.
 */
public interface Timer {
    /**
     * Times execution of callable.
     *
     * @return callable result
     * @throws Exception if callable throws
     */
    <T> T record(Callable<T> callable) throws Exception;

    /**
     * Records a pre-measured duration.
     */
    void record(Duration duration);

    /**
     * Gets percentile value.
     *
     * @param percentile value between 0.0 and 1.0
     * @return duration at percentile
     */
    Duration percentile(double percentile);
}