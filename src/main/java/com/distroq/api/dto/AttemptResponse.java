package com.distroq.api.dto;

import com.distroq.model.JobAttempt;

import java.time.Duration;
import java.time.Instant;

public record AttemptResponse(
        int attemptNumber,
        String workerId,
        Instant startedAt,
        Instant finishedAt,
        String outcome,
        String errorMessage,
        Long durationMs) {

    public static AttemptResponse from(JobAttempt attempt) {
        Instant startedAt = attempt.getStartedAt();
        Instant finishedAt = attempt.getFinishedAt();
        Long durationMs = (startedAt == null || finishedAt == null)
                ? null
                : Duration.between(startedAt, finishedAt).toMillis();

        return new AttemptResponse(
                attempt.getAttemptNumber(),
                attempt.getWorkerId(),
                startedAt,
                finishedAt,
                attempt.getOutcome().name(),
                attempt.getErrorMessage(),
                durationMs);
    }
}
