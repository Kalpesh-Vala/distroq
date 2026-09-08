package com.distroq.worker;

import com.distroq.config.DistroqProperties;
import com.distroq.model.Job;
import com.distroq.model.Priority;
import com.distroq.queue.DeliveryHandler;
import com.distroq.queue.EnqueueSource;
import com.distroq.queue.JobStreamConsumer;
import com.distroq.queue.StreamDelivery;
import com.distroq.repository.JobRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reads deliveries, runs jobs, and acknowledges only once PostgreSQL already says what happened.
 *
 * <p>That ordering is the whole of v0.5's delivery guarantee. Every path writes the outcome to the
 * database first and calls {@code XACK} second, so a crash in between costs a redelivery — never a
 * lost job. It also means the same job can run twice: {@code XACK} makes delivery at-least-once,
 * not execution exactly-once, and nothing here protects an external side effect that has already
 * happened. Idempotency keys are v0.7.
 *
 * <p>Two threads call {@link #handle}: this worker's own poll loop, and the scheduler thread
 * driving {@code PendingEntryRecovery}. They are deliberately not serialised, because blocking
 * recovery behind a thirty-second job would defeat the point of recovery. Each works on a
 * different stream entry, and {@link #isInFlight} is what stops the recovery sweep reclaiming an
 * entry this process is still working on.
 */
@Component
public class Worker implements DeliveryHandler {

    private static final Logger log = LoggerFactory.getLogger(Worker.class);

    private final JobStreamConsumer consumer;
    private final JobRepository jobRepository;
    private final JobExecutor jobExecutor;
    private final BackoffPolicy backoffPolicy;
    private final PriorityStrategy priorityStrategy;
    private final ExecutionClaimService claims;
    private final WorkerMetrics metrics;
    private final List<String> consumerNames;
    private final long heartbeatIntervalMs;
    private final Semaphore executionPermits;

    private final String workerId;
    private final AtomicBoolean running = new AtomicBoolean(true);

    /** Entries this process is executing right now, so the recovery sweep can leave them alone. */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    private ExecutorService pool;
    private ScheduledExecutorService heartbeatPool;

    public Worker(JobStreamConsumer consumer,
                  JobRepository jobRepository,
                  JobExecutor jobExecutor,
                  BackoffPolicy backoffPolicy,
                  PriorityStrategy priorityStrategy,
                  ExecutionClaimService claims,
                  WorkerMetrics metrics,
                  DistroqProperties properties) {
        this.consumer = consumer;
        this.jobRepository = jobRepository;
        this.jobExecutor = jobExecutor;
        this.backoffPolicy = backoffPolicy;
        this.priorityStrategy = priorityStrategy;
        this.claims = claims;
        this.metrics = metrics;
        int concurrency = Math.max(1, properties.worker().concurrency());
        this.heartbeatIntervalMs = Math.max(1, properties.worker().heartbeatIntervalMs());
        this.executionPermits = new Semaphore(concurrency);
        this.consumerNames = new ArrayList<>(concurrency);
        this.consumerNames.add(consumer.consumerName());
        while (consumerNames.size() < concurrency) {
            consumerNames.add(consumer.newConsumerName());
        }
        // one identity: the Redis consumer name and the worker_id in job_attempts are the same
        // string, so a pending entry can be traced to the attempt row it produced
        this.workerId = consumer.consumerName();
    }

    public String workerId() {
        return workerId;
    }

    @PostConstruct
    public void start() {
        pool = Executors.newFixedThreadPool(consumerNames.size(), r -> {
            Thread t = new Thread(r, "distroq-worker");
            t.setDaemon(true);
            return t;
        });
        heartbeatPool = Executors.newScheduledThreadPool(consumerNames.size(), r -> {
            Thread t = new Thread(r, "distroq-lease-heartbeat");
            t.setDaemon(true);
            return t;
        });
        consumerNames.forEach(name -> pool.submit(() -> runLoop(name)));
        log.info("Started {} worker loop(s) as consumers {} in group {}",
                consumerNames.size(), consumerNames, consumer.groupName());
    }

    private void runLoop(String consumerName) {
        while (running.get()) {
            try {
                boolean guardDue = priorityStrategy.guardDue();
                List<Priority> order = priorityStrategy.nextPollOrder();
                for (StreamDelivery delivery : consumer.poll(consumerName, order)) {
                    if (guardDue) {
                        log.info("Starvation guard fired after {} delivery(ies) above {}: polled {}, "
                                        + "served job {} from {}",
                                priorityStrategy.starvationThreshold(), Priority.LOWEST, order,
                                delivery.jobId(), delivery.streamPriority());
                        guardDue = false;
                    }
                    // a timed-out poll served nothing, so it is not a bypass and must not count
                    priorityStrategy.recordServed(delivery.streamPriority());
                    handle(delivery, consumerName);
                }
            } catch (Exception e) {
                if (!running.get()) {
                    return;
                }
                log.error("Worker {} loop error, backing off", consumerName, e);
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** True while this process holds the entry, whether it arrived by read or by reclaim. */
    @Override
    public boolean isInFlight(String streamKey, String entryId) {
        return inFlight.contains(inFlightKey(streamKey, entryId));
    }

    /**
     * The single execution path. Fresh deliveries and reclaimed ones both come through here, so
     * there is exactly one place that decides what a delivery means and exactly one that
     * acknowledges.
     */
    @Override
    public void handle(StreamDelivery delivery) {
        handle(delivery, workerId);
    }

    @Override
    public void handle(StreamDelivery delivery, String owner) {
        String key = inFlightKey(delivery.streamKey(), delivery.entryId());
        if (!inFlight.add(key)) {
            log.debug("Entry {} on {} is already being handled by this process, ignoring",
                    delivery.entryId(), delivery.streamKey());
            return;
        }
        try {
            dispatch(delivery, owner);
        } finally {
            inFlight.remove(key);
        }
    }

    private void dispatch(StreamDelivery delivery, String owner) {
        log.info("Delivery {} on {} -> job {} ({}, source {}) for consumer {}",
                delivery.entryId(), delivery.streamKey(), delivery.jobId(),
                delivery.streamPriority(), delivery.source(), owner);

        Optional<Job> found = jobRepository.findById(delivery.jobId());
        if (found.isEmpty()) {
            // acknowledging is right: nothing will ever make this entry runnable, and leaving it
            // pending would have every recovery sweep from now on pick it up again
            log.warn("Job {} was delivered as {} but is not in the database; acknowledging",
                    delivery.jobId(), delivery.entryId());
            consumer.acknowledge(delivery.streamKey(), delivery.entryId());
            return;
        }

        Job job = found.get();
        if (job.getPriority() != delivery.streamPriority()) {
            // PostgreSQL is authoritative; the stream tier is a routing hint that has drifted
            log.warn("Integrity warning: job {} is {} in the database but was delivered on the {} "
                            + "stream (entry {}); executing at the database priority",
                    job.getId(), job.getPriority(), delivery.streamPriority(), delivery.entryId());
        }

        switch (job.getStatus()) {
            case SUCCEEDED, DEAD_LETTERED, FAILED -> acknowledgeStale(job, delivery);
            case SCHEDULED -> handleScheduled(job, delivery, owner);
            case RETRYING -> handleRetrying(job, delivery, owner);
            case RUNNING, QUEUED -> execute(job, delivery, owner);
        }
    }

    /**
     * A user-scheduled job whose entry has arrived. Normally this means the promoter moved it
     * because its time came, and the only thing left to do is run it.
     *
     * <p>The timestamp is checked again here rather than trusted. The promotion decided due-ness
     * from a sorted-set score, and PostgreSQL holds the value the submitter actually asked for; if
     * those disagree the database wins, exactly as it does for priority. An entry that is early is
     * therefore not executed.
     *
     * <p>An early entry is left <em>pending</em>: not executed, not acknowledged, no attempt row,
     * no state change, and above all not re-scheduled — a second {@code ZADD} for a member that
     * may still be in the set is how one job becomes two entries. Doing nothing is safe because
     * the entry is already in the group's Pending Entries List, so {@code XAUTOCLAIM} redelivers
     * it after {@code claim-min-idle-ms} and it is re-evaluated then. The cost is one reclaim
     * cycle of latency and a WARN per attempt, which is the right trade for a case that should
     * only ever be reachable through clock skew or a hand-written ZADD.
     */
    private void handleScheduled(Job job, StreamDelivery delivery, String owner) {
        Instant dueAt = job.getScheduledAt();
        // a SCHEDULED row with no scheduledAt cannot have been written by this application; treat
        // it as due rather than leaving it to churn in the pending list forever
        if (dueAt != null && dueAt.isAfter(Instant.now())) {
            log.warn("Delivery {} for job {} arrived {}ms before its scheduled time {}; leaving it "
                            + "pending for redelivery rather than running early",
                    delivery.entryId(), job.getId(),
                    Duration.between(Instant.now(), dueAt).toMillis(), dueAt);
            return;
        }
        log.info("Job {} reached its scheduled time {} (entry {}, source {}); queuing for its "
                        + "first attempt",
                job.getId(), dueAt, delivery.entryId(), delivery.source());
        execute(job, delivery, owner);
    }

    /**
     * The job is finished. The entry is a redelivery of work that completed before its original
     * consumer managed to acknowledge it.
     */
    private void acknowledgeStale(Job job, StreamDelivery delivery) {
        log.info("Stale delivery {} for job {}: already {}, acknowledging without executing",
                delivery.entryId(), job.getId(), job.getStatus());
        consumer.acknowledge(delivery.streamKey(), delivery.entryId());
    }

    /**
     * RETRYING is the one status where the delivery itself decides the answer, because two very
     * different entries can arrive at a job in this state.
     *
     * <p>The scheduled retry is the entry the promotion script wrote when the backoff expired:
     * source RETRY, and stamped at or after {@code nextAttemptAt} because the script only promotes
     * members whose due time has passed. That entry <em>is</em> the next attempt and must run.
     *
     * <p>Anything else is superseded — most obviously the original delivery of a worker that
     * scheduled the retry and then died before acknowledging, which is what
     * {@code PendingEntryRecovery} will hand back. Running it would execute the job ahead of its
     * own backoff and still leave the real retry entry to arrive later. An older RETRY entry from
     * a schedule that has since been replaced falls in the same category, which is what the
     * timestamp comparison catches.
     */
    private void handleRetrying(Job job, StreamDelivery delivery, String owner) {
        Instant dueAt = job.getNextAttemptAt();
        boolean isScheduledRetry = delivery.isFrom(EnqueueSource.RETRY)
                && (dueAt == null || delivery.enqueuedAt() >= dueAt.toEpochMilli());
        if (isScheduledRetry) {
            execute(job, delivery, owner);
            return;
        }
        log.info("Delivery {} ({}) for job {} is superseded by a retry due at {}; acknowledging "
                        + "without executing",
                delivery.entryId(), delivery.source(), job.getId(), dueAt);
        consumer.acknowledge(delivery.streamKey(), delivery.entryId());
    }

    /**
     * A redelivered RUNNING job. Either its previous owner died, or that owner is alive and simply
     * slower than {@code claim-min-idle-ms}. Redis cannot tell those apart, and neither can this.
     *
     * <p>The rule: whoever holds the entry owns the current attempt. Any attempt still open is
     * closed as ABANDONED and a new one is started. {@code attemptCount} advances rather than
     * resetting, so the history stays a single ordered sequence and the retry budget is not
     * silently refilled.
     *
     * <p>This can duplicate execution and therefore duplicate side effects. That is at-least-once
     * delivery working as designed, not a bug being tolerated quietly.
     */
    private void execute(Job job, StreamDelivery delivery, String owner) {
        if (!executionPermits.tryAcquire()) {
            return;
        }
        Optional<ExecutionClaimService.Claim> won = claims.claim(job.getId(), owner,
                "Delivery " + delivery.entryId());
        if (won.isEmpty()) {
            executionPermits.release();
            log.debug("Worker {} did not obtain the database lease for job {}; leaving {} pending",
                    owner, job.getId(), delivery.entryId());
            return;
        }
        ExecutionClaimService.Claim claim = won.get();
        AtomicBoolean ownershipLost = new AtomicBoolean(false);
        metrics.workerStarted();
        ScheduledFuture<?> heartbeat = heartbeatPool == null ? null : heartbeatPool.scheduleAtFixedRate(() -> {
            try {
                if (!claims.renew(claim)) {
                    ownershipLost.set(true);
                    log.error("Worker {} lost the execution lease for job {}", owner, job.getId());
                }
            } catch (Exception e) {
                log.error("Worker {} could not renew the execution lease for job {}", owner,
                        job.getId(), e);
            }
        }, heartbeatIntervalMs, heartbeatIntervalMs, TimeUnit.MILLISECONDS);
        boolean finalized = false;
        try {
            jobExecutor.execute(claim.job());
            finalized = !ownershipLost.get() && claims.succeed(claim);
        } catch (Exception e) {
            String error = e.getMessage() == null ? e.toString() : e.getMessage();
            if (!ownershipLost.get()) {
                finalized = handleFailure(claim, error);
            }
        } finally {
            if (heartbeat != null) {
                heartbeat.cancel(false);
            }
            metrics.workerFinished();
            executionPermits.release();
        }
        if (finalized) {
            consumer.acknowledge(delivery.streamKey(), delivery.entryId());
        } else {
            log.warn("Worker {} did not finalize job {}; leaving entry {} pending", owner,
                    job.getId(), delivery.entryId());
        }
    }

    private boolean handleFailure(ExecutionClaimService.Claim claim, String error) {
        Job job = claim.job();
        if (!job.hasAttemptsRemaining()) {
            return claims.deadLetter(claim, error);
        }

        Duration delay = backoffPolicy.delayFor(job.getAttemptCount());
        Instant dueAt = Instant.now().plus(delay);
        return claims.retry(claim, error, dueAt);
    }

    private static String inFlightKey(String streamKey, String entryId) {
        return streamKey + '|' + entryId;
    }

    // fires before bean destruction closes the Redis connection, so a blocking XREADGROUP
    // aborted by shutdown is not mistaken for a genuine loop error
    @EventListener(ContextClosedEvent.class)
    public void onContextClosed() {
        running.set(false);
    }

    @PreDestroy
    public void stop() {
        log.info("Worker {} shutting down", workerId);
        running.set(false);
        if (pool == null) {
            return;
        }
        pool.shutdown();
        if (heartbeatPool != null) {
            heartbeatPool.shutdown();
        }
        try {
            if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
