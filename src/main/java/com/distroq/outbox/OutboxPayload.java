package com.distroq.outbox;

import com.distroq.model.Priority;
import com.distroq.queue.EnqueueSource;

import java.time.Instant;
import java.util.UUID;

public record OutboxPayload(UUID eventId, UUID jobId, Priority priority, EnqueueSource source,
                            Instant dueAt, Instant scheduledAt) {
}