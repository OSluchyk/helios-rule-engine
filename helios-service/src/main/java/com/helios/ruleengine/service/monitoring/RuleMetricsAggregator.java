/*
 * Copyright (c) 2025 Helios Rule Engine
 * Licensed under the Apache License, Version 2.0
 */
package com.helios.ruleengine.service.monitoring;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

/**
 * Aggregates real-time metrics for rule evaluation performance.
 * Tracks per-rule evaluation counts, match rates, and latency history.
 *
 * <p>Memory overhead: ~50 bytes per rule + 96 bytes per latency sample.
 * For 1000 rules with 3600 samples (1 hour): ~50KB + 345KB = ~395KB total.
 */
@ApplicationScoped
public class RuleMetricsAggregator {

    // Per-rule evaluation counters
    private final ConcurrentMap<String, LongAdder> ruleEvaluationCounts = new ConcurrentHashMap<>();

    // Per-rule match counters
    private final ConcurrentMap<String, LongAdder> ruleMatchCounts = new ConcurrentHashMap<>();

    // Per-rule latency tracking (for P99 calculation)
    private final ConcurrentMap<String, LatencyTracker> ruleLatencies = new ConcurrentHashMap<>();

    // Global latency history (1 hour at 1/sec = 3600 samples)
    private final RingBuffer<LatencySample> latencyHistory = new RingBuffer<>(3600);

    // Global throughput tracking
    private final RingBuffer<ThroughputSample> throughputHistory = new RingBuffer<>(3600);

    // Cache hit tracking
    private final LongAdder cacheHits = new LongAdder();
    private final LongAdder cacheMisses = new LongAdder();

    /**
     * Record a rule evaluation event.
     *
     * @param ruleCode Rule identifier
     * @param matched Whether the rule matched
     * @param durationNanos Evaluation duration in nanoseconds
     */
    public void recordEvaluation(String ruleCode, boolean matched, long durationNanos) {
        // Increment evaluation count
        ruleEvaluationCounts.computeIfAbsent(ruleCode, k -> new LongAdder()).increment();

        // Increment match count if matched
        if (matched) {
            ruleMatchCounts.computeIfAbsent(ruleCode, k -> new LongAdder()).increment();
        }

        // Record latency for this rule
        ruleLatencies.computeIfAbsent(ruleCode, k -> new LatencyTracker()).record(durationNanos);

        // Record global latency sample
        latencyHistory.add(new LatencySample(Instant.now(), durationNanos));
    }

    /**
     * Record event throughput (called once per second by background scheduler).
     *
     * @param eventsProcessed Number of events processed in the last second
     */
    public void recordThroughput(long eventsProcessed) {
        throughputHistory.add(new ThroughputSample(Instant.now(), eventsProcessed));
    }

    /**
     * Record cache hit or miss.
     *
     * @param hit true for cache hit, false for miss
     */
    public void recordCacheAccess(boolean hit) {
        if (hit) {
            cacheHits.increment();
        } else {
            cacheMisses.increment();
        }
    }

    /**
     * Get the top N most frequently evaluated rules.
     *
     * @param topN Number of top rules to return
     * @return List of hot rules sorted by evaluation count (descending)
     */
    public List<HotRule> getHotRules(int topN) {
        return ruleEvaluationCounts.entrySet().stream()
            .map(entry -> {
                String ruleCode = entry.getKey();
                long evaluations = entry.getValue().sum();
                long matches = ruleMatchCounts.getOrDefault(ruleCode, new LongAdder()).sum();
                double matchRate = evaluations > 0 ? (double) matches / evaluations : 0.0;

                return new HotRule(ruleCode, evaluations, matches, matchRate);
            })
            .sorted(Comparator.comparingLong(HotRule::evaluationCount).reversed())
            .limit(topN)
            .collect(Collectors.toList());
    }

    /**
     * Get rules with slow P99 latency (>100ms threshold).
     *
     * @return List of slow rules with P99 latency above threshold
     */
    public List<SlowRule> getSlowRules() {
        return getSlowRules(100_000_000L); // 100ms in nanoseconds
    }

    /**
     * Get rules with P99 latency above the specified threshold.
     *
     * @param thresholdNanos P99 latency threshold in nanoseconds
     * @return List of slow rules
     */
    public List<SlowRule> getSlowRules(long thresholdNanos) {
        return ruleLatencies.entrySet().stream()
            .map(entry -> {
                String ruleCode = entry.getKey();
                LatencyTracker tracker = entry.getValue();
                long p99 = tracker.getP99();

                if (p99 > thresholdNanos) {
                    long evaluations = ruleEvaluationCounts.getOrDefault(ruleCode, new LongAdder()).sum();
                    return new SlowRule(ruleCode, p99, tracker.getAvg(), tracker.getMax(), evaluations);
                }
                return null;
            })
            .filter(Objects::nonNull)
            .sorted(Comparator.comparingLong(SlowRule::p99Nanos).reversed())
            .collect(Collectors.toList());
    }

    /**
     * Get latency history for the last hour.
     *
     * @return List of latency samples
     */
    public List<LatencySample> getLatencyHistory() {
        return latencyHistory.getAll();
    }

    /**
     * Get throughput history for the last hour.
     *
     * @return List of throughput samples
     */
    public List<ThroughputSample> getThroughputHistory() {
        return throughputHistory.getAll();
    }

    /**
     * Get current cache hit rate.
     *
     * @return Cache hit rate (0.0 to 1.0)
     */
    public double getCacheHitRate() {
        long hits = cacheHits.sum();
        long misses = cacheMisses.sum();
        long total = hits + misses;
        return total > 0 ? (double) hits / total : 0.0;
    }

    /**
     * Get overall metrics summary.
     *
     * @return Aggregated metrics
     */
    public MetricsSummary getSummary() {
        long totalEvaluations = ruleEvaluationCounts.values().stream()
            .mapToLong(LongAdder::sum)
            .sum();

        long totalMatches = ruleMatchCounts.values().stream()
            .mapToLong(LongAdder::sum)
            .sum();

        double overallMatchRate = totalEvaluations > 0 ? (double) totalMatches / totalEvaluations : 0.0;

        // Calculate average throughput from last 60 seconds
        List<ThroughputSample> recentThroughput = throughputHistory.getAll();
        long avgEventsPerMinute = 0;
        if (!recentThroughput.isEmpty()) {
            long totalEvents = recentThroughput.stream()
                .mapToLong(ThroughputSample::eventsProcessed)
                .sum();
            avgEventsPerMinute = (totalEvents * 60) / recentThroughput.size();
        }

        return new MetricsSummary(
            totalEvaluations,
            totalMatches,
            overallMatchRate,
            ruleEvaluationCounts.size(),
            avgEventsPerMinute,
            getCacheHitRate()
        );
    }

    /**
     * Reset all metrics (for testing or manual reset).
     */
    public void reset() {
        ruleEvaluationCounts.clear();
        ruleMatchCounts.clear();
        ruleLatencies.clear();
        latencyHistory.clear();
        throughputHistory.clear();
        cacheHits.reset();
        cacheMisses.reset();
    }

    // ===== Data Structures =====

    /**
     * Ring buffer for fixed-size time-series data.
     */
    private static class RingBuffer<T> {
        private final List<T> buffer;
        private final int capacity;
        private int writeIndex = 0;
        private boolean wrapped = false;

        RingBuffer(int capacity) {
            this.capacity = capacity;
            this.buffer = new ArrayList<>(Collections.nCopies(capacity, null));
        }

        synchronized void add(T item) {
            buffer.set(writeIndex, item);
            writeIndex = (writeIndex + 1) % capacity;
            if (writeIndex == 0) {
                wrapped = true;
            }
        }

        synchronized List<T> getAll() {
            if (!wrapped) {
                return new ArrayList<>(buffer.subList(0, writeIndex));
            }

            List<T> result = new ArrayList<>(capacity);
            for (int i = 0; i < capacity; i++) {
                int index = (writeIndex + i) % capacity;
                T item = buffer.get(index);
                if (item != null) {
                    result.add(item);
                }
            }
            return result;
        }

        synchronized void clear() {
            Collections.fill(buffer, null);
            writeIndex = 0;
            wrapped = false;
        }
    }

    /**
     * Tracks latency percentiles for a single rule.
     * Uses a bounded array (max 1000 samples) for memory efficiency.
     */
    private static class LatencyTracker {
        private final List<Long> samples = new ArrayList<>(1000);
        private long sum = 0;
        private long max = 0;
        private int count = 0;

        synchronized void record(long nanos) {
            if (samples.size() < 1000) {
                samples.add(nanos);
            } else {
                // Replace oldest sample (circular buffer)
                samples.set(count % 1000, nanos);
            }
            sum += nanos;
            max = Math.max(max, nanos);
            count++;
        }

        synchronized long getP99() {
            if (samples.isEmpty()) return 0;

            List<Long> sorted = new ArrayList<>(samples);
            Collections.sort(sorted);
            int p99Index = (int) Math.ceil(sorted.size() * 0.99) - 1;
            return sorted.get(Math.max(0, p99Index));
        }

        synchronized long getAvg() {
            return count > 0 ? sum / count : 0;
        }

        synchronized long getMax() {
            return max;
        }
    }

    // ===== DTOs =====

    public record LatencySample(Instant timestamp, long durationNanos) {}

    public record ThroughputSample(Instant timestamp, long eventsProcessed) {}

    public record HotRule(String ruleCode, long evaluationCount, long matchCount, double matchRate) {}

    public record SlowRule(String ruleCode, long p99Nanos, long avgNanos, long maxNanos, long evaluationCount) {}

    public record MetricsSummary(
        long totalEvaluations,
        long totalMatches,
        double overallMatchRate,
        int uniqueRulesEvaluated,
        long avgEventsPerMinute,
        double cacheHitRate
    ) {}
}
