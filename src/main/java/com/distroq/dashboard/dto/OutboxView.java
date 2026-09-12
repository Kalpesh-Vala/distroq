package com.distroq.dashboard.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Outbox lifecycle as an operator reads it.
 *
 * <p>No payload, ever. The list an operator leaves open during an incident is the list that ends
 * up in a screenshot, and a payload column is how job data leaves the building. {@code lastError}
 * survives because the relay writes it from an exception this application raised — but it is still
 * truncated and stripped of control characters before it is sent, because an HTTP client exception
 * happily quotes the response body it failed on.
 *
 * <p>{@code retryableFailed} and {@code terminalFailed} are deliberately not one number.
 * A PENDING event that has failed twice is still being retried and needs nobody; a FAILED event
 * has spent its budget and will not move again until a human says so.
 */
public record OutboxView(Totals totals,
                         Map<String, Long> countsByStatus,
                         Map<String, Long> countsByEventType,
                         Map<String, Long> unpublishedByAge,
                         Latency latency,
                         PageView<EventRow> events) {

    public record Totals(long pending,
                         long publishing,
                         long published,
                         long retryableFailed,
                         long terminalFailed,
                         Long oldestUnpublishedAgeMs,
                         Instant oldestUnpublishedAt,
                         long operatorRetries) {
    }

    /**
     * @param approximate always true: the percentiles are over the most recent publications, not
     *                    over the whole table, and the UI says so
     */
    public record Latency(Long p50Ms, Long p95Ms, int sampleSize, boolean approximate) {
    }

    public record EventRow(String eventId,
                           String eventType,
                           String aggregateId,
                           String status,
                           int attemptCount,
                           int operatorRetryCount,
                           Instant createdAt,
                           Instant availableAt,
                           Instant lockedUntil,
                           Instant publishedAt,
                           Instant terminalFailedAt,
                           String lastError,
                           long ageMs,
                           boolean payloadRedacted) {
    }
}
