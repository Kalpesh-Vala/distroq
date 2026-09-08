package com.distroq.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "idempotency_keys")
public class IdempotencyKey {

    @Id
    @Column(name = "idempotency_key", length = 128)
    private String key;

    @Column(nullable = false, length = 64)
    private String requestHash;

    @Column(nullable = false)
    private UUID jobId;

    @Column(nullable = false)
    private Instant createdAt;

    protected IdempotencyKey() {
    }

    public static IdempotencyKey create(String key, String requestHash, UUID jobId) {
        IdempotencyKey record = new IdempotencyKey();
        record.key = key;
        record.requestHash = requestHash;
        record.jobId = jobId;
        record.createdAt = Instant.now();
        return record;
    }

    public String getKey() { return key; }
    public String getRequestHash() { return requestHash; }
    public UUID getJobId() { return jobId; }
    public Instant getCreatedAt() { return createdAt; }
}