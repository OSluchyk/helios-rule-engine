package com.helios.ruleengine.infra.metrics.impl.inmemory;


import com.helios.ruleengine.infra.metrics.Counter;

import java.util.concurrent.atomic.AtomicLong;

final class InMemoryCounter implements Counter {
    private final AtomicLong value = new AtomicLong(0);
    private final String name;

    InMemoryCounter(String name) {
        this.name = name;
    }

    @Override
    public void increment() {
        increment(1);
    }

    @Override
    public void increment(long amount) {
        value.addAndGet(amount);
    }

    @Override
    public long count() {
        return value.get();
    }
}
