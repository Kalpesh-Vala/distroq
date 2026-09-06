package com.distroq.worker;

import com.distroq.model.Job;
import com.distroq.model.JobAttempt;
import com.distroq.model.Priority;
import com.distroq.queue.Dequeued;
import com.distroq.queue.JobQueue;
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
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class Worker {

    private static final Logger log = LoggerFactory.getLogger(Worker.class);
    private static final Duration POP_TIMEOUT = Duration.ofSeconds(2);

    private final JobQueue jobQueue;
    private final JobRepository jobRepository;
    private final JobAttemptRepository jobAttemptRepository;
    private final JobExecutor jobExecutor;
    private final BackoffPolicy backoffPolicy;
    private final DeadLetterWriter deadLetterWriter;
    private final PriorityStrategy priorityStrategy;

    private final String workerId = "worker-" + UUID.randomUUID().toString().substring(0, 8);
    private final AtomicBoolean running = new AtomicBoolean(true);

    private ExecutorService pool;

    public Worker(JobQueue jobQueue,
                  JobRepository jobRepository,
                  JobAttemptRepository jobAttemptRepository,
                  JobExecutor jobExecutor,
                  BackoffPolicy backoffPolicy,
                  DeadLetterWriter deadLetterWriter,
                  PriorityStrategy priorityStrategy) {
        this.jobQueue = jobQueue;
        this.jobRepository = jobRepository;
        this.jobAttemptRepository = jobAttemptRepository;
        this.jobExecutor = jobExecutor;
        this.backoffPolicy = backoffPolicy;
        this.deadLetterWriter = deadLetterWriter;
        this.priorityStrategy = priorityStrategy;
    }

    @PostConstruct
    public void start() {
        pool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, workerId);
            t.setDaemon(true);
            return t;
        });
        pool.submit(this::runLoop);
        log.info("Worker {} started", workerId);
    }

    private void runLoop() {
        while (running.get()) {
            try {
                boolean guardDue = priorityStrategy.guardDue();
                List<Priority> order = priorityStrategy.nextPollOrder();
                Dequeued dequeued = jobQueue.dequeue(order, POP_TIMEOUT);
                if (dequeued != null) {
                    if (guardDue) {
                        log.info("Starvation guard fired after {} dequeue(s) above {}: polled {}, "
                                        + "served job {} from {}",
                                priorityStrategy.starvationThreshold(), Priority.LOWEST, order,
                                dequeued.jobId(), dequeued.tier());
                    }
                    // a timed-out poll served nothing, so it is not a bypass and must not count
                    priorityStrategy.recordServed(dequeued.tier());
                    process(dequeued.jobId(), dequeued.tier());
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

    private void process(UUID jobId, Priority servedFrom) {
        Optional<Job> found = jobRepository.findById(jobId);
        if (found.isEmpty()) {
            log.warn("Job {} was queued but is not in the database, skipping", jobId);
            return;
        }

        Job job = found.get();
        job.markRunning();
        jobRepository.save(job);
        int attemptNumber = job.getAttemptCount();
        Instant startedAt = job.getStartedAt();
        log.info("Job {} ({}, served from {}) RUNNING on {}, attempt {} of {}",
                job.getId(), job.getType(), servedFrom, workerId, attemptNumber, job.getMaxAttempts());

        try {
            jobExecutor.execute(job);
            job.markSucceeded();
            jobRepository.save(job);
            jobAttemptRepository.save(JobAttempt.success(
                    job.getId(), workerId, attemptNumber, startedAt, Instant.now()));
            log.info("Job {} SUCCEEDED on attempt {}", job.getId(), attemptNumber);
        } catch (Exception e) {
            String error = e.getMessage() == null ? e.toString() : e.getMessage();
            jobAttemptRepository.save(JobAttempt.failure(
                    job.getId(), workerId, attemptNumber, startedAt, Instant.now(), error));
            handleFailure(job, attemptNumber, error);
        }
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
        // second instance of the dual write from JobController.submit - see NOTES.md
        jobRepository.save(job);
        // the tier travels with the job into the delayed set, so the promotion does not have to
        // read it back out of Postgres to know which list to push to
        jobQueue.scheduleAt(job.getId(), job.getPriority(), dueAt);
        log.warn("Job {} ({}) failed attempt {} of {} ({}), RETRYING in {}ms",
                job.getId(), job.getPriority(), attemptNumber, job.getMaxAttempts(), error,
                delay.toMillis());
    }

    // fires before bean destruction closes the Redis connection, so a BRPOP
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
