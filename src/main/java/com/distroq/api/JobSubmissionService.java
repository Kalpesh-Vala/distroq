package com.distroq.api;

import com.distroq.api.dto.SubmitJobRequest;
import com.distroq.config.DistroqProperties;
import com.distroq.model.IdempotencyKey;
import com.distroq.model.Job;
import com.distroq.model.JobStatus;
import com.distroq.model.Priority;
import com.distroq.outbox.OutboxEventType;
import com.distroq.outbox.OutboxService;
import com.distroq.queue.EnqueueSource;
import com.distroq.repository.DeadLetterRepository;
import com.distroq.repository.IdempotencyKeyRepository;
import com.distroq.repository.JobRepository;
import jakarta.persistence.EntityManager;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class JobSubmissionService {

    private final JobRepository jobRepository;
    private final DeadLetterRepository deadLetterRepository;
    private final IdempotencyKeyRepository idempotencyRepository;
    private final OutboxService outboxService;
    private final IdempotencyRequestHasher hasher;
    private final EntityManager entityManager;
    private final int defaultMaxAttempts;
    private final int replayAttempts;

    public JobSubmissionService(JobRepository jobRepository,
                                DeadLetterRepository deadLetterRepository,
                                IdempotencyKeyRepository idempotencyRepository,
                                OutboxService outboxService,
                                IdempotencyRequestHasher hasher,
                                EntityManager entityManager,
                                DistroqProperties properties) {
        this.jobRepository = jobRepository;
        this.deadLetterRepository = deadLetterRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.outboxService = outboxService;
        this.hasher = hasher;
        this.entityManager = entityManager;
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
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
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
        return new SubmissionResult(job, false);
    }

    @Transactional
    public Job replay(UUID id) {
        Job job = jobRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No job with id " + id));
        if (job.getStatus() != JobStatus.DEAD_LETTERED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only DEAD_LETTERED jobs can be replayed; job " + id + " is " + job.getStatus());
        }
        job.prepareForReplay(replayAttempts);
        deadLetterRepository.findById(id).ifPresent(deadLetter -> {
            deadLetter.markReplayed();
            deadLetterRepository.save(deadLetter);
        });
        outboxService.create(job, OutboxEventType.ENQUEUE_REPLAY, EnqueueSource.REPLAY, null);
        return job;
    }

    private int resolveMaxAttempts(Integer requested) {
        if (requested == null) {
            return defaultMaxAttempts;
        }
        if (requested < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "maxAttempts must be at least 1, got " + requested);
        }
        return requested;
    }

    private Priority resolvePriority(String requested) {
        return Priority.parse(requested)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "priority must be one of " + Priority.validValues()
                                + " (case-insensitive), got '" + requested + "'"));
    }

    static String normalizeKey(String raw) {
        if (raw == null) {
            return null;
        }
        String key = raw.trim();
        if (key.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Idempotency-Key must not be blank");
        }
        if (key.length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Idempotency-Key must be at most 128 characters");
        }
        return key;
    }

    public record SubmissionResult(Job job, boolean replayed) {
    }
}