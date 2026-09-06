package com.distroq.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One row per execution attempt. Holds the raw {@code jobId} rather than a {@code @ManyToOne},
 * so DTO mapping cannot trip over a lazy proxy outside a session.
 */
@Entity
@Table(name = "job_attempts")
public class JobAttempt {

    @Id
    private UUID id;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    private String workerId;

    private int attemptNumber;

    private Instant startedAt;
    private Instant finishedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AttemptOutcome outcome;

    @Column(columnDefinition = "text")
    private String errorMessage;

    protected JobAttempt() {
        // for JPA
    }

    public static JobAttempt success(UUID jobId, String workerId, int attemptNumber,
                                     Instant startedAt, Instant finishedAt) {
        return of(jobId, workerId, attemptNumber, startedAt, finishedAt, AttemptOutcome.SUCCESS, null);
    }

    public static JobAttempt failure(UUID jobId, String workerId, int attemptNumber,
                                     Instant startedAt, Instant finishedAt, String errorMessage) {
        return of(jobId, workerId, attemptNumber, startedAt, finishedAt, AttemptOutcome.FAILURE, errorMessage);
    }

    private static JobAttempt of(UUID jobId, String workerId, int attemptNumber, Instant startedAt,
                                 Instant finishedAt, AttemptOutcome outcome, String errorMessage) {
        JobAttempt attempt = new JobAttempt();
        attempt.id = UUID.randomUUID();
        attempt.jobId = jobId;
        attempt.workerId = workerId;
        attempt.attemptNumber = attemptNumber;
        attempt.startedAt = startedAt;
        attempt.finishedAt = finishedAt;
        attempt.outcome = outcome;
        attempt.errorMessage = errorMessage;
        return attempt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getJobId() {
        return jobId;
    }

    public String getWorkerId() {
        return workerId;
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public AttemptOutcome getOutcome() {
        return outcome;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
