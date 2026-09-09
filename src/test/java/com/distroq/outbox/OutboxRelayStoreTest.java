package com.distroq.outbox;

import com.distroq.TestProperties;
import com.distroq.config.DistroqProperties;
import com.distroq.model.OutboxEvent;
import com.distroq.model.OutboxStatus;
import com.distroq.repository.OutboxEventRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OutboxRelayStoreTest {

    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);

    @Test
    void failureBecomesTerminalAtTheConfiguredAttemptLimit() {
        OutboxRelayStore store = new OutboxRelayStore(repository, TestProperties.defaults());
        OutboxEvent event = event();
        when(repository.findById(event.getId())).thenReturn(Optional.of(event));

        OutboxRelayStore.FailureResult result = null;
        for (int attempt = 0; attempt < TestProperties.OUTBOX.maxAttempts(); attempt++) {
            result = store.markFailed(event.getId(), new IllegalStateException("Redis unavailable"));
        }

        assertThat(result).isNotNull();
        assertThat(result.attemptCount()).isEqualTo(TestProperties.OUTBOX.maxAttempts());
        assertThat(result.terminal()).isTrue();
        assertThat(result.ceiling()).isEqualTo(TestProperties.OUTBOX.maxAttempts());
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(event.getLastError()).isEqualTo("Redis unavailable");
    }

    @Test
    void claimingMarksTheEventPublishingForTheLeaseDuration() {
        OutboxRelayStore store = new OutboxRelayStore(repository, TestProperties.defaults());
        OutboxEvent event = event();
        when(repository.claimable(any(Instant.class), anyInt())).thenReturn(List.of(event));

        List<UUID> claimed = store.claimBatch();

        assertThat(claimed).containsExactly(event.getId());
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHING);
        assertThat(event.getLockedUntil()).isAfter(Instant.now());
    }

    @Test
    void claimingReturnsNothingWhenOnlyTerminalEventsRemain() {
        OutboxRelayStore store = new OutboxRelayStore(repository, TestProperties.defaults());
        when(repository.claimable(any(Instant.class), anyInt())).thenReturn(List.of());

        assertThat(store.claimBatch()).isEmpty();
    }

    @Test
    void publicationMarksTheEventPublishedExactlyOnce() {
        OutboxRelayStore store = new OutboxRelayStore(repository, TestProperties.defaults());
        OutboxEvent event = event();
        when(repository.findById(event.getId())).thenReturn(Optional.of(event));

        store.markPublished(event.getId());
        Instant first = event.getPublishedAt();
        store.markPublished(event.getId());

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(event.getPublishedAt()).isEqualTo(first);
    }

    @Test
    void anOperatorRetryRaisesTheCeilingRatherThanResettingTheAttemptCount() {
        DistroqProperties properties = TestProperties.of(
                new DistroqProperties.Outbox(500L, 100, 30_000L, 2, 604_800_000L, 30, 90,
                        3_600_000L, 500, true, false));
        OutboxRelayStore store = new OutboxRelayStore(repository, properties);
        OutboxEvent event = event();
        when(repository.findById(event.getId())).thenReturn(Optional.of(event));

        store.markFailed(event.getId(), new IllegalStateException("down"));
        OutboxRelayStore.FailureResult terminal =
                store.markFailed(event.getId(), new IllegalStateException("down"));
        event.operatorRetry("restored", Instant.now());
        OutboxRelayStore.FailureResult afterRepair =
                store.markFailed(event.getId(), new IllegalStateException("down again"));

        assertThat(terminal.terminal()).isTrue();
        assertThat(afterRepair.terminal()).isFalse();
        assertThat(afterRepair.attemptCount()).isEqualTo(3);
        assertThat(afterRepair.ceiling()).isEqualTo(4);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
    }

    private static OutboxEvent event() {
        UUID id = UUID.randomUUID();
        return OutboxEvent.create(id, UUID.randomUUID(), OutboxEventType.ENQUEUE_SUBMIT,
                "{\"eventId\":\"" + id + "\"}", Instant.now());
    }
}