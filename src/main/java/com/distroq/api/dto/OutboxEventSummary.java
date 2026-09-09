package com.distroq.api.dto;

import com.distroq.model.OutboxEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * The list view of an outbox event.
 *
 * <p>No payload. The list endpoint is the one an operator leaves open on a wall display during an
 * incident, and a payload column is how job data ends up in a screenshot. {@code lastError} is
 * included because it is written by the relay from an exception this application raised, not from
 * user data.
 */
public record OutboxEventSummary(UUID eventId,
                                 String eventType,
                                 UUID aggregateId,
                                 String status,
                                 int attemptCount,
                                 int operatorRetryCount,
                                 Instant createdAt,
                                 Instant availableAt,
                                 Instant lockedUntil,
                                 Instant publishedAt,
                                 Instant terminalFailedAt,
                                 Instant lastOperatorRetryAt,
                                 String lastError,
                                 long ageMs) {

    public static OutboxEventSummary from(OutboxEvent event, Instant now) {
        return new OutboxEventSummary(
                event.getId(),
                event.getEventType().name(),
                event.getAggregateId(),
                event.getStatus().name(),
                event.getAttemptCount(),
                event.getOperatorRetryCount(),
                event.getCreatedAt(),
                event.getAvailableAt(),
                event.getLockedUntil(),
                event.getPublishedAt(),
                event.getTerminalFailedAt(),
                event.getLastOperatorRetryAt(),
                event.getLastError(),
                Math.max(0L, Duration.between(event.getCreatedAt(), now).toMillis()));
    }
}
