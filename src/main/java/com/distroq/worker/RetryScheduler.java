package com.distroq.worker;

import com.distroq.config.DistroqProperties;
import com.distroq.queue.JobQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sweeps due jobs out of the delayed sorted set and onto the pending list.
 *
 * <p>Deliberately does not touch job status: the promotion is a queue-level move, and the
 * observable transition is {@code RETRYING -> RUNNING}, which the worker already performs.
 */
@Component
public class RetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(RetryScheduler.class);

    private final JobQueue jobQueue;
    private final int batchLimit;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public RetryScheduler(JobQueue jobQueue, DistroqProperties properties) {
        this.jobQueue = jobQueue;
        this.batchLimit = properties.retry().promoteBatchSize();
    }

    @Scheduled(fixedDelayString = "${distroq.retry.poll-interval-ms:1000}")
    public void sweep() {
        if (!running.get()) {
            return;
        }
        try {
            int promoted = jobQueue.promoteDueJobs(Instant.now(), batchLimit);
            if (promoted > 0) {
                log.info("Promoted {} due job(s) from the delayed set", promoted);
            } else {
                log.debug("No due jobs to promote");
            }
        } catch (Exception e) {
            // never propagate: an escaping exception cancels all future executions of this task
            if (!running.get()) {
                log.debug("Retry sweep aborted during shutdown");
                return;
            }
            log.error("Retry sweep failed, retrying on the next tick", e);
        }
    }

    // matches Worker: fires before bean destruction closes the Redis connection
    @EventListener(ContextClosedEvent.class)
    public void onContextClosed() {
        running.set(false);
    }
}
