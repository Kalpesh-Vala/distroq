package com.distroq.model;

import com.distroq.outbox.OutboxEventType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

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
        return event;
    }

    public void claimUntil(Instant until) {
        this.lockedUntil = until;
    }

    public void markPublished(Instant at) {
        this.publishedAt = at;
        this.lockedUntil = null;
        this.lastError = null;
    }

    public void markFailed(String error) {
        this.attemptCount++;
        this.lastError = error;
        this.lockedUntil = null;
    }

    public UUID getId() { return id; }
    public UUID getAggregateId() { return aggregateId; }
    public OutboxEventType getEventType() { return eventType; }
    public String getPayload() { return payload; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getPublishedAt() { return publishedAt; }
    public Instant getLockedUntil() { return lockedUntil; }
    public int getAttemptCount() { return attemptCount; }
    public String getLastError() { return lastError; }
}