package com.distroq.api;

import com.distroq.api.dto.SubmitJobRequest;
import com.distroq.api.error.ApiException;
import com.distroq.api.error.ErrorCode;
import com.distroq.config.DistroqProperties;
import com.distroq.metrics.DistroqMetrics;
import com.distroq.model.IdempotencyKey;
import com.distroq.model.Job;
import com.distroq.model.JobStatus;
import com.distroq.model.Priority;
import com.distroq.observability.Events;
import com.distroq.observability.LogContext;
import com.distroq.outbox.OutboxEventType;
import com.distroq.outbox.OutboxService;
import com.distroq.queue.EnqueueSource;
import com.distroq.repository.DeadLetterRepository;
import com.distroq.repository.IdempotencyKeyRepository;
import com.distroq.repository.JobRepository;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class JobSubmissionService {

    private static final Logger log = LoggerFactory.getLogger(JobSubmissionService.class);

    private final JobRepository jobRepository;
    private final DeadLetterRepository deadLetterRepository;
    private final IdempotencyKeyRepository idempotencyRepository;
    private final OutboxService outboxService;
    private final IdempotencyRequestHasher hasher;
    private final EntityManager entityManager;
    private final DistroqMetrics metrics;
    private final int defaultMaxAttempts;
    private final int replayAttempts;

    public JobSubmissionService(JobRepository jobRepository,
                                DeadLetterRepository deadLetterRepository,
                                IdempotencyKeyRepository idempotencyRepository,
                                OutboxService outboxService,
                                IdempotencyRequestHasher hasher,
                                EntityManager entityManager,
                                DistroqMetrics metrics,
                                DistroqProperties properties) {
        this.jobRepository = jobRepository;
        this.deadLetterRepository = deadLetterRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.outboxService = outboxService;
        this.hasher = hasher;
        this.entityManager = entityManager;
        this.metrics = metrics;
        this.defaultMaxAttempts = properties.retry().defaultMaxAttempts();
        this.replayAttempts = properties.dlq().replayAttempts();
    }

    @Transactional
    public SubmissionResult submit(SubmitJobRequest request, String rawIdempotencyKey) {
        int maxAttempts = resolveMaxAttempts(request.maxAttempts());
        Priority priority = resolvePriority(request.priority());
        Instant scheduledAt = ScheduledAtParser.parse(request.scheduledAt());
        String key = normalizeKey(rawIdempotencyKey);
        String requestHash = hasher.hash(request.type(), request.payload(), maxAttempts, priority,
                scheduledAt);

        if (key != null) {
            entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(hashtextextended(?1, 0))")
                    .setParameter(1, key)
                    .getSingleResult();
            Optional<IdempotencyKey> existing = idempotencyRepository.findById(key);
            if (existing.isPresent()) {
                IdempotencyKey record = existing.get();
                if (!record.getRequestHash().equals(requestHash)) {
                    // the key is echoed because the caller chose it and already knows it; nothing
                    // about the earlier request's payload is disclosed
                    throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT,
                            "Idempotency key '" + key + "' was already used for a different request");
                }
                Job original = jobRepository.findById(record.getJobId())
                        .orElseThrow(() -> new IllegalStateException(
                                "Idempotency key " + key + " references a missing job"));
                return new SubmissionResult(original, true);
            }
        }

        Job job = jobRepository.save(
                Job.create(request.type(), request.payload(), maxAttempts, priority, scheduledAt));
        if (key != null) {
            idempotencyRepository.save(IdempotencyKey.create(key, requestHash, job.getId()));
        }
        if (job.isScheduled()) {
            outboxService.create(job, OutboxEventType.SCHEDULE_USER_JOB, EnqueueSource.SCHEDULED,
                    scheduledAt);
        } else {
            outboxService.create(job, OutboxEventType.ENQUEUE_SUBMIT, EnqueueSource.SUBMIT, null);
        }
        metrics.jobSubmitted(job);
        try (LogContext ignored = LogContext
                .event(job.isScheduled() ? Events.JOB_SCHEDULED : Events.JOB_SUBMITTED)
                .job(job.getId()).priority(job.getPriority()).status(job.getStatus())) {
            // the type is a label the submitter chose; the payload never appears
            log.info("Accepted job of type {} with a budget of {} attempt(s){}", job.getType(),
                    maxAttempts, job.isScheduled() ? " for " + scheduledAt : "");
        }
        return new SubmissionResult(job, false);
    }

    @Transactional
    public Job replay(UUID id) {
        Job job = jobRepository.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.JOB_NOT_FOUND,
                        "No job with id " + id));
        if (job.getStatus() != JobStatus.DEAD_LETTERED) {
            throw new ApiException(ErrorCode.JOB_NOT_DEAD_LETTERED,
                    "Only DEAD_LETTERED jobs can be replayed; job " + id + " is " + job.getStatus());
        }
        job.prepareForReplay(replayAttempts);
        deadLetterRepository.findById(id).ifPresent(deadLetter -> {
            deadLetter.markReplayed();
            deadLetterRepository.save(deadLetter);
        });
        outboxService.create(job, OutboxEventType.ENQUEUE_REPLAY, EnqueueSource.REPLAY, null);
        metrics.jobReplayed(job);
        try (LogContext ignored = LogContext.event(Events.JOB_REPLAYED)
                .job(job.getId()).priority(job.getPriority()).status(job.getStatus())) {
            log.info("Replayed dead-lettered job with {} further attempt(s)", replayAttempts);
        }
        return job;
    }

    private int resolveMaxAttempts(Integer requested) {
        if (requested == null) {
            return defaultMaxAttempts;
        }
        if (requested < 1) {
            throw new ApiException(ErrorCode.INVALID_MAX_ATTEMPTS,
                    "maxAttempts must be at least 1, got " + requested);
        }
        return requested;
    }

    private Priority resolvePriority(String requested) {
        return Priority.parse(requested)
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_PRIORITY,
                        "priority must be one of " + Priority.validValues()
                                + " (case-insensitive), got '" + requested + "'"));
    }

    static String normalizeKey(String raw) {
        if (raw == null) {
            return null;
        }
        String key = raw.trim();
        if (key.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_IDEMPOTENCY_KEY,
                    "Idempotency-Key must not be blank");
        }
        if (key.length() > 128) {
            throw new ApiException(ErrorCode.INVALID_IDEMPOTENCY_KEY,
                    "Idempotency-Key must be at most 128 characters");
        }
        return key;
    }

    public record SubmissionResult(Job job, boolean replayed) {
    }
}