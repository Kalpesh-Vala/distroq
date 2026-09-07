package com.distroq.worker;

import com.distroq.model.AttemptOutcome;
import com.distroq.model.Job;
import com.distroq.model.JobAttempt;
import com.distroq.model.Priority;
import com.distroq.queue.DeliveryHandler;
import com.distroq.queue.EnqueueSource;
import com.distroq.queue.JobQueue;
import com.distroq.queue.JobStreamConsumer;
import com.distroq.queue.StreamDelivery;
import com.distroq.repository.JobAttemptRepository;
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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    private final JobQueue jobQueue;
    private final JobStreamConsumer consumer;
    private final JobRepository jobRepository;
    private final JobAttemptRepository jobAttemptRepository;
    private final JobExecutor jobExecutor;
    private final BackoffPolicy backoffPolicy;
    private final DeadLetterWriter deadLetterWriter;
    private final PriorityStrategy priorityStrategy;

    private final String workerId;
    private final AtomicBoolean running = new AtomicBoolean(true);

    /** Entries this process is executing right now, so the recovery sweep can leave them alone. */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    private ExecutorService pool;

    public Worker(JobQueue jobQueue,
                  JobStreamConsumer consumer,
                  JobRepository jobRepository,
                  JobAttemptRepository jobAttemptRepository,
                  JobExecutor jobExecutor,
                  BackoffPolicy backoffPolicy,
                  DeadLetterWriter deadLetterWriter,
                  PriorityStrategy priorityStrategy) {
        this.jobQueue = jobQueue;
        this.consumer = consumer;
        this.jobRepository = jobRepository;
        this.jobAttemptRepository = jobAttemptRepository;
        this.jobExecutor = jobExecutor;
        this.backoffPolicy = backoffPolicy;
        this.deadLetterWriter = deadLetterWriter;
        this.priorityStrategy = priorityStrategy;
        // one identity: the Redis consumer name and the worker_id in job_attempts are the same
        // string, so a pending entry can be traced to the attempt row it produced
        this.workerId = consumer.consumerName();
    }

    public String workerId() {
        return workerId;
    }

    @PostConstruct
    public void start() {
        pool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, workerId);
            t.setDaemon(true);
            return t;
        });
        pool.submit(this::runLoop);
        log.info("Worker {} started as consumer {} in group {}",
                workerId, consumer.consumerName(), consumer.groupName());
    }

    private void runLoop() {
        while (running.get()) {
            try {
                boolean guardDue = priorityStrategy.guardDue();
                List<Priority> order = priorityStrategy.nextPollOrder();
                for (StreamDelivery delivery : consumer.poll(order)) {
                    if (guardDue) {
                        log.info("Starvation guard fired after {} delivery(ies) above {}: polled {}, "
                                        + "served job {} from {}",
                                priorityStrategy.starvationThreshold(), Priority.LOWEST, order,
                                delivery.jobId(), delivery.streamPriority());
                        guardDue = false;
                    }
                    // a timed-out poll served nothing, so it is not a bypass and must not count
                    priorityStrategy.recordServed(delivery.streamPriority());
                    handle(delivery);
                }
            } catch (Exception e) {
                if (!running.get()) {
                    return;
                }
                log.error("Worker {} loop error, backing off", workerId, e);
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
        String key = inFlightKey(delivery.streamKey(), delivery.entryId());
        if (!inFlight.add(key)) {
            log.debug("Entry {} on {} is already being handled by this process, ignoring",
                    delivery.entryId(), delivery.streamKey());
            return;
        }
        try {
            dispatch(delivery);
        } finally {
            inFlight.remove(key);
        }
    }

    private void dispatch(StreamDelivery delivery) {
        log.info("Delivery {} on {} -> job {} ({}, source {}) for consumer {}",
                delivery.entryId(), delivery.streamKey(), delivery.jobId(),
                delivery.streamPriority(), delivery.source(), workerId);

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
            case RETRYING -> handleRetrying(job, delivery);
            case RUNNING -> reclaimAndExecute(job, delivery);
            case QUEUED -> execute(job, delivery);
        }
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
    private void handleRetrying(Job job, StreamDelivery delivery) {
        Instant dueAt = job.getNextAttemptAt();
        boolean isScheduledRetry = delivery.isFrom(EnqueueSource.RETRY)
                && (dueAt == null || delivery.enqueuedAt() >= dueAt.toEpochMilli());
        if (isScheduledRetry) {
            execute(job, delivery);
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
    private void reclaimAndExecute(Job job, StreamDelivery delivery) {
        List<JobAttempt> open = jobAttemptRepository
                .findByJobIdAndOutcomeOrderByAttemptNumberAsc(job.getId(), AttemptOutcome.IN_PROGRESS);
        for (JobAttempt attempt : open) {
            attempt.abandon(Instant.now(), "Delivery " + delivery.entryId() + " reclaimed by "
                    + workerId + "; " + attempt.getWorkerId() + " never reported an outcome");
            jobAttemptRepository.save(attempt);
            log.warn("Marked attempt {} of job {} ABANDONED (was owned by {})",
                    attempt.getAttemptNumber(), job.getId(), attempt.getWorkerId());
        }
        log.warn("Reclaimed RUNNING job {} on entry {}; starting attempt {} on {}",
                job.getId(), delivery.entryId(), job.getAttemptCount() + 1, workerId);
        execute(job, delivery);
    }

    private void execute(Job job, StreamDelivery delivery) {
        job.markRunning();
        jobRepository.save(job);
        int attemptNumber = job.getAttemptCount();
        Instant startedAt = job.getStartedAt();

        // open the attempt row before running, so a worker that disappears leaves evidence
        JobAttempt attempt = jobAttemptRepository.save(
                JobAttempt.started(job.getId(), workerId, attemptNumber, startedAt));

        log.info("Job {} ({}, served from {}) RUNNING on {}, attempt {} of {}",
                job.getId(), job.getType(), delivery.streamPriority(), workerId,
                attemptNumber, job.getMaxAttempts());

        try {
            jobExecutor.execute(job);
            job.markSucceeded();
            jobRepository.save(job);
            attempt.succeed(Instant.now());
            jobAttemptRepository.save(attempt);
            log.info("Job {} SUCCEEDED on attempt {}", job.getId(), attemptNumber);
        } catch (Exception e) {
            String error = e.getMessage() == null ? e.toString() : e.getMessage();
            attempt.fail(Instant.now(), error);
            jobAttemptRepository.save(attempt);
            handleFailure(job, attemptNumber, error);
        }
        // last, and only now: the database already describes the outcome, so a crash before this
        // point redelivers an entry the duplicate-delivery rules above know how to dismiss
        consumer.acknowledge(delivery.streamKey(), delivery.entryId());
    }

    private void handleFailure(Job job, int attemptNumber, String error) {
        if (!job.hasAttemptsRemaining()) {
            deadLetterWriter.deadLetter(job, error);
            log.error("Job {} DEAD_LETTERED after {} attempt(s): {}",
                    job.getId(), attemptNumber, error);
            return;
        }

        Duration delay = backoffPolicy.delayFor(job.getAttemptCount());
        Instant dueAt = Instant.now().plus(delay);
        job.markRetrying(error, dueAt);
        // second instance of the dual write from JobController.submit - see NOTES.md. Streams did
        // not fix it: the row commits here and the sorted-set write is still a separate system.
        jobRepository.save(job);
        jobQueue.scheduleAt(job.getId(), job.getPriority(), dueAt);
        log.warn("Job {} ({}) failed attempt {} of {} ({}), RETRYING in {}ms",
                job.getId(), job.getPriority(), attemptNumber, job.getMaxAttempts(), error,
                delay.toMillis());
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
