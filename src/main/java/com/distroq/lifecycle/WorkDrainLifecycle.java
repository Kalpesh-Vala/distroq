package com.distroq.lifecycle;

import com.distroq.config.DistroqProperties;
import com.distroq.observability.Events;
import com.distroq.observability.LogContext;
import com.distroq.worker.WorkerMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Step three of shutdown: let the work that is already running finish.
 *
 * <p>The phase places this after Spring Boot's graceful web shutdown ({@code MAX_VALUE - 1024})
 * and after the connector stops ({@code MAX_VALUE - 2048}), so by the time this runs no new HTTP
 * request can arrive and no poll loop is claiming anything. What is left is the jobs that were
 * mid-execution when shutdown began, and the only useful thing to do about them is wait.
 *
 * <p>If the wait runs out, nothing is forced. An in-flight job is not failed, not marked
 * succeeded, and its stream entry is not acknowledged: the process simply stops holding it. The
 * execution lease then expires on its own clock and {@code XAUTOCLAIM} hands the entry to another
 * consumer after {@code claim-min-idle-ms}, which is the same path a hard kill takes. Recording an
 * outcome we did not observe would be the one genuinely unrecoverable thing available here.
 */
@Component
public class WorkDrainLifecycle implements SmartLifecycle {

    /** Late enough that HTTP has drained, early enough that the connection pools are still open. */
    static final int PHASE = Integer.MAX_VALUE - 4096;

    private static final long POLL_INTERVAL_MS = 100;

    private static final Logger log = LoggerFactory.getLogger(WorkDrainLifecycle.class);

    private final WorkerMetrics workerMetrics;
    private final long workerTimeoutMs;
    private volatile boolean running;

    public WorkDrainLifecycle(WorkerMetrics workerMetrics, DistroqProperties properties) {
        this.workerMetrics = workerMetrics;
        this.workerTimeoutMs = properties.shutdown().workerTimeoutMs();
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        long startedAt = System.currentTimeMillis();
        int remaining = awaitDrain(startedAt);
        long elapsed = System.currentTimeMillis() - startedAt;
        try (LogContext ignored = LogContext.event(Events.APPLICATION_SHUTDOWN_COMPLETED)
                .durationMs(elapsed)) {
            if (remaining == 0) {
                log.info("All in-flight job executions finished; Redis and database connections "
                        + "close next");
            } else {
                log.warn("{} job execution(s) were still running after {}ms; leaving their stream "
                                + "entries unacknowledged and their leases to expire so another "
                                + "consumer can reclaim them", remaining, workerTimeoutMs);
            }
        }
    }

    private int awaitDrain(long startedAt) {
        int active = workerMetrics.activeWorkers();
        while (active > 0 && System.currentTimeMillis() - startedAt < workerTimeoutMs) {
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return workerMetrics.activeWorkers();
            }
            active = workerMetrics.activeWorkers();
        }
        return active;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
