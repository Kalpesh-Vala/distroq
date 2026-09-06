package com.distroq.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "jobs")
public class Job {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String type;

    @Column(columnDefinition = "text")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobStatus status;

    private int attemptCount;

    // DB-level default so ddl-auto: update can add this NOT NULL column to existing v0.1 rows
    @ColumnDefault("3")
    @Column(nullable = false)
    private int maxAttempts;

    @Column(columnDefinition = "text")
    private String errorMessage;

    private Instant createdAt;
    private Instant updatedAt;
    private Instant startedAt;
    private Instant finishedAt;
    private Instant nextAttemptAt;

    protected Job() {
        // for JPA
    }

    public static Job create(String type, String payload, int maxAttempts) {
        Instant now = Instant.now();
        Job job = new Job();
        job.id = UUID.randomUUID();
        job.type = type;
        job.payload = payload;
        job.status = JobStatus.QUEUED;
        job.attemptCount = 0;
        job.maxAttempts = maxAttempts;
        job.createdAt = now;
        job.updatedAt = now;
        return job;
    }

    public boolean hasAttemptsRemaining() {
        return attemptCount < maxAttempts;
    }

    public void markRunning() {
        Instant now = Instant.now();
        this.status = JobStatus.RUNNING;
        this.attemptCount++;
        this.startedAt = now;
        this.updatedAt = now;
        this.nextAttemptAt = null;
    }

    public void markSucceeded() {
        Instant now = Instant.now();
        this.status = JobStatus.SUCCEEDED;
        this.finishedAt = now;
        this.updatedAt = now;
        this.nextAttemptAt = null;
    }

    /** Non-terminal: finishedAt stays unset because the job has not finished, only this attempt has. */
    public void markRetrying(String error, Instant nextAttemptAt) {
        Instant now = Instant.now();
        this.status = JobStatus.RETRYING;
        this.errorMessage = error;
        this.nextAttemptAt = nextAttemptAt;
        this.updatedAt = now;
    }

    public void markFailed(String error) {
        Instant now = Instant.now();
        this.status = JobStatus.FAILED;
        this.errorMessage = error;
        this.finishedAt = now;
        this.updatedAt = now;
        this.nextAttemptAt = null;
    }

    public UUID getId() {
        return id;
    }

    public String getType() {
        return type;
    }

    public String getPayload() {
        return payload;
    }

    public JobStatus getStatus() {
        return status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }
}
