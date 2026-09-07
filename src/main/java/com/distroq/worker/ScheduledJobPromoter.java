package com.distroq.worker;

import com.distroq.config.DistroqProperties;
import com.distroq.queue.ScheduledJobQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sweeps jobs whose requested execution time has arrived out of the scheduled sorted set and onto
 * their tier's stream.
 *
 * <p>The v0.6 counterpart to {@link RetryScheduler}, and deliberately as dumb as it is. It moves
 * members and counts them. It does not execute jobs, open attempt rows, apply backoff, or write to
 * PostgreSQL at all — every one of those belongs to the worker that receives the entry, and doing
 * any of them from this thread would mean two components racing to own a job's lifecycle. The
 * observable transition stays {@code SCHEDULED -> RUNNING}, performed by the worker that actually
 * holds the entry.
 *
 * <p>A poller rather than a timer. {@code Thread.sleep}, {@code ScheduledExecutorService.schedule}
 * and any other in-memory arrangement all have the same defect: the schedule lives in the process
 * and dies with it. Polling a Redis sorted set costs one {@code ZRANGEBYSCORE} per tick against a
 * set that is usually empty, and survives a restart because nothing about the schedule was ever
 * in this JVM.
 */
@Component
public class ScheduledJobPromoter {

    private static final Logger log = LoggerFactory.getLogger(ScheduledJobPromoter.class);

    private final ScheduledJobQueue scheduledJobQueue;
    private final int batchLimit;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public ScheduledJobPromoter(ScheduledJobQueue scheduledJobQueue, DistroqProperties properties) {
        this.scheduledJobQueue = scheduledJobQueue;
        this.batchLimit = properties.scheduling().promoteBatchSize();
    }

    @Scheduled(fixedDelayString = "${distroq.scheduling.poll-interval-ms:1000}")
    public void sweep() {
        if (!running.get()) {
            return;
        }
        try {
            int promoted = scheduledJobQueue.promoteDueJobs(Instant.now(), batchLimit);
            if (promoted > 0) {
                log.info("Promoted {} due scheduled job(s) from {}",
                        promoted, scheduledJobQueue.key());
            } else {
                log.debug("No scheduled jobs due");
            }
        } catch (Exception e) {
            // never propagate: an escaping exception cancels all future executions of this task,
            // which would strand every scheduled job until the next restart
            if (!running.get()) {
                log.debug("Scheduled-job sweep aborted during shutdown");
                return;
            }
            log.error("Scheduled-job sweep failed, retrying on the next tick", e);
        }
    }

    // matches Worker, RetryScheduler and PendingEntryRecovery: fires before bean destruction
    // closes the Redis connection
    @EventListener(ContextClosedEvent.class)
    public void onContextClosed() {
        running.set(false);
    }
}
