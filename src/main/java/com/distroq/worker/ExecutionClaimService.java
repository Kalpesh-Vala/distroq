package com.distroq.worker;

import com.distroq.config.DistroqProperties;
import com.distroq.model.AttemptOutcome;
import com.distroq.model.DeadLetter;
import com.distroq.model.Job;
import com.distroq.model.JobAttempt;
import com.distroq.outbox.OutboxEventType;
import com.distroq.outbox.OutboxService;
import com.distroq.queue.EnqueueSource;
import com.distroq.repository.DeadLetterRepository;
import com.distroq.repository.JobAttemptRepository;
import com.distroq.repository.JobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class ExecutionClaimService {

    private final JobRepository jobRepository;
    private final JobAttemptRepository attemptRepository;
    private final DeadLetterRepository deadLetterRepository;
    private final OutboxService outboxService;
    private final Duration leaseDuration;

    public ExecutionClaimService(JobRepository jobRepository,
                                 JobAttemptRepository attemptRepository,
                                 DeadLetterRepository deadLetterRepository,
                                 OutboxService outboxService,
                                 DistroqProperties properties) {
        this.jobRepository = jobRepository;
        this.attemptRepository = attemptRepository;
        this.deadLetterRepository = deadLetterRepository;
        this.outboxService = outboxService;
        this.leaseDuration = Duration.ofMillis(properties.worker().executionLeaseMs());
    }

    @Transactional
    public Optional<Claim> claim(UUID jobId, String owner, String deliveryDescription) {
        Instant now = Instant.now();
        UUID attemptId = UUID.randomUUID();
        if (jobRepository.claimExecution(jobId, owner, attemptId, now, now.plus(leaseDuration)) != 1) {
            return Optional.empty();
        }

        for (JobAttempt previous : attemptRepository
                .findByJobIdAndOutcomeOrderByAttemptNumberAsc(jobId, AttemptOutcome.IN_PROGRESS)) {
            previous.abandon(now, deliveryDescription + " claimed by " + owner
                    + "; " + previous.getWorkerId() + " no longer owns the database lease");
        }

        Job job = jobRepository.findById(jobId).orElseThrow();
        JobAttempt attempt = attemptRepository.save(
                JobAttempt.started(attemptId, jobId, owner, job.getAttemptCount(), now));
        return Optional.of(new Claim(job, attempt));
    }

    @Transactional
    public boolean renew(Claim claim) {
        Instant now = Instant.now();
        return jobRepository.renewExecutionLease(claim.job().getId(), claim.attempt().getWorkerId(),
                claim.attempt().getId(), now, now.plus(leaseDuration)) == 1;
    }

    @Transactional
    public boolean succeed(Claim claim) {
        Instant now = Instant.now();
        if (jobRepository.completeExecution(claim.job().getId(), claim.attempt().getWorkerId(),
                claim.attempt().getId(), now) != 1) {
            return false;
        }
        claim.attempt().succeed(now);
        attemptRepository.save(claim.attempt());
        return true;
    }

    @Transactional
    public boolean retry(Claim claim, String error, Instant dueAt) {
        Instant now = Instant.now();
        if (jobRepository.scheduleRetry(claim.job().getId(), claim.attempt().getWorkerId(),
                claim.attempt().getId(), error, dueAt, now) != 1) {
            return false;
        }
        claim.attempt().fail(now, error);
        attemptRepository.save(claim.attempt());
        outboxService.create(claim.job(), OutboxEventType.SCHEDULE_RETRY, EnqueueSource.RETRY, dueAt);
        return true;
    }

    @Transactional
    public boolean deadLetter(Claim claim, String error) {
        Instant now = Instant.now();
        if (jobRepository.deadLetterExecution(claim.job().getId(), claim.attempt().getWorkerId(),
                claim.attempt().getId(), error, now) != 1) {
            return false;
        }
        claim.attempt().fail(now, error);
        attemptRepository.save(claim.attempt());
        DeadLetter deadLetter = deadLetterRepository.findById(claim.job().getId())
                .map(existing -> {
                    existing.markDeadLetteredAgain(error);
                    return existing;
                })
                .orElseGet(() -> DeadLetter.of(claim.job().getId(), error));
        deadLetterRepository.save(deadLetter);
        return true;
    }

    public record Claim(Job job, JobAttempt attempt) {
    }
}