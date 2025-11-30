package com.helios.ruleengine.infra.metrics.impl.inmemory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import com.helios.ruleengine.infra.metrics.Timer;

/**
 * In-memory implementation of {@link Timer} for testing.
 * Stores all recorded durations for assertions and percentile calculations.
 * Thread-safe implementation using CopyOnWriteArrayList.
 */
final class InMemoryTimer implements Timer {

    private final String name;
    private final List<Duration> recordings;

    InMemoryTimer(String name) {
        this.name = name;
        this.recordings = new CopyOnWriteArrayList<>();
    }

    @Override
    public <T> T record(Callable<T> callable) throws Exception {
        long startNanos = System.nanoTime();
        try {
            return callable.call();
        } finally {
            long elapsedNanos = System.nanoTime() - startNanos;
            recordings.add(Duration.ofNanos(elapsedNanos));
        }
    }

    @Override
    public void record(Duration duration) {
        if (duration.isNegative()) {
            throw new IllegalArgumentException("Cannot record negative duration: " + duration);
        }
        recordings.add(duration);
    }

    @Override
    public Duration percentile(double percentile) {
        if (recordings.isEmpty()) {
            return Duration.ZERO;
        }

        double p = Math.max(0.0, Math.min(1.0, percentile));

        List<Duration> sorted = new ArrayList<>(recordings);
        Collections.sort(sorted);

        if (sorted.size() == 1) {
            return sorted.get(0);
        }

        double index = (sorted.size() - 1) * p;
        int lowerIndex = (int) Math.floor(index);
        int upperIndex = (int) Math.ceil(index);

        if (lowerIndex == upperIndex) {
            return sorted.get(lowerIndex);
        }

        Duration lower = sorted.get(lowerIndex);
        Duration upper = sorted.get(upperIndex);
        double fraction = index - lowerIndex;

        long interpolatedNanos = lower.toNanos() +
                (long) ((upper.toNanos() - lower.toNanos()) * fraction);

        return Duration.ofNanos(interpolatedNanos);
    }

    // Test utility methods

    List<Duration> getRecordings() {
        return Collections.unmodifiableList(new ArrayList<>(recordings));
    }

    List<Duration> getSortedRecordings() {
        List<Duration> sorted = new ArrayList<>(recordings);
        Collections.sort(sorted);
        return Collections.unmodifiableList(sorted);
    }

    int count() {
        return recordings.size();
    }

    Duration totalTime() {
        return recordings.stream()
                .reduce(Duration.ZERO, Duration::plus);
    }

    Duration mean() {
        if (recordings.isEmpty()) {
            return Duration.ZERO;
        }
        long totalNanos = totalTime().toNanos();
        return Duration.ofNanos(totalNanos / recordings.size());
    }

    Duration min() {
        return recordings.stream()
                .min(Comparator.naturalOrder())
                .orElse(Duration.ZERO);
    }

    Duration max() {
        return recordings.stream()
                .max(Comparator.naturalOrder())
                .orElse(Duration.ZERO);
    }

    boolean hasRecordingAbove(Duration threshold) {
        return recordings.stream()
                .anyMatch(d -> d.compareTo(threshold) > 0);
    }

    String getName() {
        return name;
    }

    void reset() {
        recordings.clear();
    }

    @Override
    public String toString() {
        if (recordings.isEmpty()) {
            return String.format("InMemoryTimer{name='%s', count=0}", name);
        }
        return String.format(
                "InMemoryTimer{name='%s', count=%d, min=%s, max=%s, mean=%s, p99=%s}",
                name,
                count(),
                formatDuration(min()),
                formatDuration(max()),
                formatDuration(mean()),
                formatDuration(percentile(0.99))
        );
    }

    private String formatDuration(Duration d) {
        if (d.toNanos() < 1_000) {
            return d.toNanos() + "ns";
        } else if (d.toNanos() < 1_000_000) {
            return String.format("%.1fµs", d.toNanos() / 1_000.0);
        } else if (d.toMillis() < 1_000) {
            return String.format("%.1fms", d.toNanos() / 1_000_000.0);
        } else {
            return String.format("%.2fs", d.toMillis() / 1000.0);
        }
    }
}