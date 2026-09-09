package com.distroq.model;

import com.distroq.outbox.OutboxEventType;
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
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    private UUID id;

    @Column(nullable = false, length = 100)
    private String aggregateType;

    private UUID aggregateId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 100)
    private OutboxEventType eventType;

    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Column(nullable = false)
    private Instant availableAt;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant publishedAt;
    private Instant lockedUntil;

    @Column(nullable = false)
    private int attemptCount;

    @Column(columnDefinition = "text")
    private String lastError;

    /**
     * v0.8. Authoritative from here on; {@link #publishedAt}, {@link #lockedUntil} and
     * {@link #attemptCount} are kept for audit rather than as the state machine.
     */
    @Enumerated(EnumType.STRING)
    @ColumnDefault("'PENDING'")
    @Column(nullable = false, length = 50)
    private OutboxStatus status;

    @ColumnDefault("0")
    @Column(nullable = false)
    private int operatorRetryCount;

    private Instant terminalFailedAt;
    private Instant lastOperatorRetryAt;

    @Column(columnDefinition = "text")
    private String lastOperatorReason;

    protected OutboxEvent() {
    }

    public static OutboxEvent create(UUID id, UUID jobId, OutboxEventType eventType,
                                     String payload, Instant availableAt) {
        OutboxEvent event = new OutboxEvent();
        event.id = id;
        event.aggregateType = "Job";
        event.aggregateId = jobId;
        event.eventType = eventType;
        event.payload = payload;
        event.availableAt = availableAt;
        event.createdAt = Instant.now();
        event.status = OutboxStatus.PENDING;
        return event;
    }

    public void claimUntil(Instant until) {
        this.lockedUntil = until;
        this.status = OutboxStatus.PUBLISHING;
    }

    public void markPublished(Instant at) {
        this.publishedAt = at;
        this.lockedUntil = null;
        this.lastError = null;
        this.status = OutboxStatus.PUBLISHED;
    }

    /**
     * One failed relay attempt. The event goes terminal only once it has spent the whole budget
     * its current operator generation is entitled to.
     *
     * @return true if this failure was the terminal one
     */
    public boolean markFailed(String error, int maxAttempts) {
        this.attemptCount++;
        this.lastError = error;
        this.lockedUntil = null;
        if (attemptCount >= terminalCeiling(maxAttempts)) {
            this.status = OutboxStatus.FAILED;
            this.terminalFailedAt = Instant.now();
            return true;
        }
        this.status = OutboxStatus.PENDING;
        return false;
    }

    /**
     * The attempt count at which this event goes terminal.
     *
     * <p>{@code attemptCount} is cumulative and is never reset, so the ceiling moves instead: each
     * operator retry grants one further budget of {@code maxAttempts}. History stays readable —
     * attempt 214 really is the 214th attempt — and {@code max-attempts} keeps meaning "tries
     * before a human is asked", which is the only reading an alert can act on.
     */
    public int terminalCeiling(int maxAttempts) {
        return maxAttempts * (operatorRetryCount + 1);
    }

    /**
     * Re-arm a terminal event. The identity is deliberately untouched: the same event ID means
     * that the Redis deduplication marker, if it is still alive, still refuses a second
     * publication of work that did reach Redis before the relay lost its answer.
     *
     * <p>{@code terminalFailedAt} is kept rather than cleared. It records when this event last
     * needed a human, and the audit row records who answered.
     */
    public void operatorRetry(String reason, Instant at) {
        if (status != OutboxStatus.FAILED) {
            throw new IllegalStateException("Only a FAILED outbox event can be retried by an "
                    + "operator; event " + id + " is " + status);
        }
        this.status = OutboxStatus.PENDING;
        this.lockedUntil = null;
        this.availableAt = at;
        this.operatorRetryCount++;
        this.lastOperatorRetryAt = at;
        this.lastOperatorReason = reason;
    }

    /** Reconciliation repair for a relay lease that expired without ever reaching a verdict. */
    public void releaseExpiredLock() {
        this.lockedUntil = null;
        this.status = publishedAt == null ? OutboxStatus.PENDING : OutboxStatus.PUBLISHED;
    }

    /** Reconciliation repair for a row that published but whose status never caught up. */
    public void reconcilePublishedStatus() {
        this.status = OutboxStatus.PUBLISHED;
        this.lockedUntil = null;
    }

    public boolean isTerminal() {
        return status == OutboxStatus.FAILED;
    }

    public UUID getId() { return id; }
    public String getAggregateType() { return aggregateType; }
    public UUID getAggregateId() { return aggregateId; }
    public OutboxEventType getEventType() { return eventType; }
    public String getPayload() { return payload; }
    public Instant getAvailableAt() { return availableAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getPublishedAt() { return publishedAt; }
    public Instant getLockedUntil() { return lockedUntil; }
    public int getAttemptCount() { return attemptCount; }
    public String getLastError() { return lastError; }
    public OutboxStatus getStatus() { return status; }
    public int getOperatorRetryCount() { return operatorRetryCount; }
    public Instant getTerminalFailedAt() { return terminalFailedAt; }
    public Instant getLastOperatorRetryAt() { return lastOperatorRetryAt; }
    public String getLastOperatorReason() { return lastOperatorReason; }
}