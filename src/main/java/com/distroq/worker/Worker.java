package com.distroq.worker;

import com.distroq.model.Job;
import com.distroq.model.JobAttempt;
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

    private final String workerId = "worker-" + UUID.randomUUID().toString().substring(0, 8);
    private final AtomicBoolean running = new AtomicBoolean(true);

    private ExecutorService pool;

    public Worker(JobQueue jobQueue,
                  JobRepository jobRepository,
                  JobAttemptRepository jobAttemptRepository,
                  JobExecutor jobExecutor,
                  BackoffPolicy backoffPolicy) {
        this.jobQueue = jobQueue;
        this.jobRepository = jobRepository;
        this.jobAttemptRepository = jobAttemptRepository;
        this.jobExecutor = jobExecutor;
        this.backoffPolicy = backoffPolicy;
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
                UUID jobId = jobQueue.dequeue(POP_TIMEOUT);
                if (jobId != null) {
                    process(jobId);
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

    private void process(UUID jobId) {
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
        log.info("Job {} ({}) RUNNING on {}, attempt {} of {}",
                job.getId(), job.getType(), workerId, attemptNumber, job.getMaxAttempts());

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
            job.markFailed(error);
            jobRepository.save(job);
            log.error("Job {} permanently FAILED after {} attempt(s): {}",
                    job.getId(), attemptNumber, error);
            return;
        }

        Duration delay = backoffPolicy.delayFor(job.getAttemptCount());
        Instant dueAt = Instant.now().plus(delay);
        job.markRetrying(error, dueAt);
        // second instance of the dual write from JobController.submit - see NOTES.md
        jobRepository.save(job);
        jobQueue.scheduleAt(job.getId(), dueAt);
        log.warn("Job {} failed attempt {} of {} ({}), RETRYING in {}ms",
                job.getId(), attemptNumber, job.getMaxAttempts(), error, delay.toMillis());
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
