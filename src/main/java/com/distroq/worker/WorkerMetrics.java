package com.distroq.worker;

import com.distroq.config.DistroqProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class WorkerMetrics {

    private final int concurrency;
    private final AtomicInteger activeWorkers = new AtomicInteger();
    private final AtomicLong reclaimedEntries = new AtomicLong();

    public WorkerMetrics(DistroqProperties properties) {
        this.concurrency = Math.max(1, properties.worker().concurrency());
    }

    public int concurrency() { return concurrency; }
    public int activeWorkers() { return activeWorkers.get(); }
    public long reclaimedEntries() { return reclaimedEntries.get(); }
    public void workerStarted() { activeWorkers.incrementAndGet(); }
    public void workerFinished() { activeWorkers.decrementAndGet(); }
    public void reclaimed(int count) { reclaimedEntries.addAndGet(count); }
}