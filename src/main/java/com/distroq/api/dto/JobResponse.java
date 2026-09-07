package com.distroq.api.dto;

import com.distroq.model.Job;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public record JobResponse(
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
        /** The execution time originally requested, or null. Survives retries, the DLQ and replay. */
        Instant scheduledAt,
        Long durationMs) {

    public static JobResponse from(Job job) {
        Instant startedAt = job.getStartedAt();
        Instant finishedAt = job.getFinishedAt();
        Long durationMs = (startedAt == null || finishedAt == null)
                ? null
                : Duration.between(startedAt, finishedAt).toMillis();

        return new JobResponse(
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
                durationMs);
    }
}
