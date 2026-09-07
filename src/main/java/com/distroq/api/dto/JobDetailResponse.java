package com.distroq.api.dto;

import com.distroq.model.Job;
import com.distroq.model.JobAttempt;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Single-job view. The list endpoint deliberately returns {@link JobResponse} instead, so
 * listing 50 jobs does not fan out into 50 attempt queries.
 */
public record JobDetailResponse(
        UUID id,
        String type,
        String payload,
        String status,
        String priority,
        int attemptCount,
        int maxAttempts,
        String errorMessage,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        Instant nextAttemptAt,
        /**
         * The execution time originally requested, or null. Sits alongside {@code nextAttemptAt}
         * rather than replacing it: this is what was asked for, that is when the next automatic
         * retry is due, and a scheduled job that has failed once has both.
         */
        Instant scheduledAt,
        Long durationMs,
        List<AttemptResponse> attempts) {

    public static JobDetailResponse from(Job job, List<JobAttempt> attempts) {
        Instant startedAt = job.getStartedAt();
        Instant finishedAt = job.getFinishedAt();
        Long durationMs = (startedAt == null || finishedAt == null)
                ? null
                : Duration.between(startedAt, finishedAt).toMillis();

        return new JobDetailResponse(
                job.getId(),
                job.getType(),
                job.getPayload(),
                job.getStatus().name(),
                job.getPriority().name(),
                job.getAttemptCount(),
                job.getMaxAttempts(),
                job.getErrorMessage(),
                job.getCreatedAt(),
                startedAt,
                finishedAt,
                job.getNextAttemptAt(),
                job.getScheduledAt(),
                durationMs,
                attempts.stream().map(AttemptResponse::from).toList());
    }
}
