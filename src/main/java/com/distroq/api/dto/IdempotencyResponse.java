package com.distroq.api.dto;

import com.distroq.model.IdempotencyKey;
import com.distroq.model.Job;

import java.time.Instant;
import java.util.UUID;

public record IdempotencyResponse(String idempotencyKey, String requestHash, UUID jobId,
                                  Instant createdAt, String jobStatus) {

    public static IdempotencyResponse from(IdempotencyKey record, Job job) {
        return new IdempotencyResponse(record.getKey(), record.getRequestHash(), record.getJobId(),
                record.getCreatedAt(), job.getStatus().name());
    }
}