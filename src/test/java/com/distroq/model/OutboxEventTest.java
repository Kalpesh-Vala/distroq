package com.distroq.model;

import com.distroq.outbox.OutboxEventType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxEventTest {

    @Test
    void newEventStartsUnpublishedAndUnattempted() {
        OutboxEvent event = event();

        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.getLockedUntil()).isNull();
        assertThat(event.getAttemptCount()).isZero();
        assertThat(event.getLastError()).isNull();
    }

    @Test
    void successfulPublicationClearsTheLeaseAndMarksPublished() {
        OutboxEvent event = event();
        event.claimUntil(Instant.now().plusSeconds(30));
        Instant publishedAt = Instant.now();

        event.markPublished(publishedAt);

        assertThat(event.getPublishedAt()).isEqualTo(publishedAt);
        assertThat(event.getLockedUntil()).isNull();
    }

    @Test
    void failureIncrementsAttemptsRetainsTheEventAndClearsTheLease() {
        OutboxEvent event = event();
        event.claimUntil(Instant.now().plusSeconds(30));

        event.markFailed("redis unavailable");

        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.getAttemptCount()).isEqualTo(1);
        assertThat(event.getLastError()).isEqualTo("redis unavailable");
        assertThat(event.getLockedUntil()).isNull();
    }

    private OutboxEvent event() {
        UUID id = UUID.randomUUID();
        return OutboxEvent.create(id, UUID.randomUUID(), OutboxEventType.ENQUEUE_SUBMIT,
                "{\"eventId\":\"" + id + "\"}", Instant.now());
    }
}