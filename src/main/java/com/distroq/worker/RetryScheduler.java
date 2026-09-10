package com.distroq.worker;

import com.distroq.config.DistroqProperties;
import com.distroq.health.SubsystemHealth;
import com.distroq.lifecycle.ShutdownState;
import com.distroq.queue.JobQueue;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

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
    private final ShutdownState shutdownState;
    private final SubsystemHealth subsystemHealth;
    private final int batchLimit;

    public RetryScheduler(JobQueue jobQueue, ShutdownState shutdownState,
                          SubsystemHealth subsystemHealth, DistroqProperties properties) {
        this.jobQueue = jobQueue;
        this.shutdownState = shutdownState;
        this.subsystemHealth = subsystemHealth;
        this.batchLimit = properties.retry().promoteBatchSize();
    }

    @PostConstruct
    void register() {
        subsystemHealth.started(SubsystemHealth.Subsystem.SCHEDULER);
    }

    @Scheduled(fixedDelayString = "${distroq.retry.poll-interval-ms:1000}")
    public void sweep() {
        if (!shutdownState.isRunning()) {
            return;
        }
        try {
            int promoted = jobQueue.promoteDueJobs(Instant.now(), batchLimit);
            subsystemHealth.succeeded(SubsystemHealth.Subsystem.SCHEDULER);
            if (promoted > 0) {
                log.info("Promoted {} due job(s) from the delayed set", promoted);
            } else {
                log.debug("No due jobs to promote");
            }
        } catch (Exception e) {
            // never propagate: an escaping exception cancels all future executions of this task
            if (!shutdownState.isRunning()) {
                log.debug("Retry sweep aborted during shutdown");
                return;
            }
            subsystemHealth.failed(SubsystemHealth.Subsystem.SCHEDULER, e);
            log.error("Retry sweep failed, retrying on the next tick", e);
        }
    }
}
