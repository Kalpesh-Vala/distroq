package com.distroq.worker;

import com.distroq.model.Job;
import com.distroq.queue.JobQueue;
import com.distroq.repository.JobRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
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
    private final JobExecutor jobExecutor;

    // unused for now; later versions attribute jobs to the worker that ran them
    private final String workerId = "worker-" + UUID.randomUUID().toString().substring(0, 8);
    private final AtomicBoolean running = new AtomicBoolean(true);

    private ExecutorService pool;

    public Worker(JobQueue jobQueue, JobRepository jobRepository, JobExecutor jobExecutor) {
        this.jobQueue = jobQueue;
        this.jobRepository = jobRepository;
        this.jobExecutor = jobExecutor;
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
        log.info("Job {} ({}) RUNNING on {}", job.getId(), job.getType(), workerId);

        try {
            jobExecutor.execute(job);
            job.markSucceeded();
            log.info("Job {} SUCCEEDED", job.getId());
        } catch (Exception e) {
            job.markFailed(e.getMessage());
            log.warn("Job {} FAILED: {}", job.getId(), e.getMessage());
        }
        jobRepository.save(job);
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
