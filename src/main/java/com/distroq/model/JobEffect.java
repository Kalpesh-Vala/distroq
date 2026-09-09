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
 * One row per logical side effect that opted into the DistroQ effect protocol.
 *
 * <p>{@code effectKey} is the primary key, and that is the whole mechanism: claiming an effect is
 * an insert that either wins or collides, so two workers racing the same logical effect are
 * separated by PostgreSQL rather than by luck.
 *
 * <p>The key is deliberately not the Redis Stream entry ID and not always the attempt number.
 * Redelivery gives the same logical work a new entry ID, and the point of the ledger is that the
 * same logical work keeps the same identity across deliveries. See NOTES.md.
 */
@Entity
@Table(name = "job_effects")
public class JobEffect {

    @Id
    @Column(name = "effect_key", length = 255)
    private String effectKey;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(nullable = false)
    private int attemptNumber;

    @Column(nullable = false, length = 100)
    private String effectType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private EffectStatus status;

    @Column(length = 64)
    private String responseHash;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant completedAt;

    @Column(columnDefinition = "text")
    private String errorMessage;

    protected JobEffect() {
        // for JPA
    }

    public void complete(String responseHash, Instant at) {
        this.status = EffectStatus.COMPLETED;
        this.responseHash = responseHash;
        this.completedAt = at;
        this.errorMessage = null;
    }

    public void fail(String error, Instant at) {
        this.status = EffectStatus.FAILED;
        this.completedAt = at;
        this.errorMessage = error;
    }

    /** A previously FAILED key handed back to a new attempt. The ledger row keeps its identity. */
    public void reclaim(int attemptNumber, Instant at) {
        this.status = EffectStatus.STARTED;
        this.attemptNumber = attemptNumber;
        this.createdAt = at;
        this.completedAt = null;
        this.responseHash = null;
        this.errorMessage = null;
    }

    public String getEffectKey() { return effectKey; }
    public UUID getJobId() { return jobId; }
    public int getAttemptNumber() { return attemptNumber; }
    public String getEffectType() { return effectType; }
    public EffectStatus getStatus() { return status; }
    public String getResponseHash() { return responseHash; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }
    public String getErrorMessage() { return errorMessage; }
}
