package com.distroq.outbox;

import com.distroq.TestProperties;
import com.distroq.model.OutboxEvent;
import com.distroq.repository.OutboxEventRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OutboxRelayStoreTest {

    @Test
    void failureBecomesTerminalAtTheConfiguredAttemptLimit() {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        OutboxRelayStore store = new OutboxRelayStore(repository, TestProperties.defaults());
        UUID eventId = UUID.randomUUID();
        OutboxEvent event = OutboxEvent.create(eventId, UUID.randomUUID(),
                OutboxEventType.ENQUEUE_SUBMIT, "{}", Instant.now());
        when(repository.findById(eventId)).thenReturn(Optional.of(event));

        OutboxRelayStore.FailureResult result = null;
        for (int attempt = 0; attempt < TestProperties.OUTBOX.maxAttempts(); attempt++) {
            result = store.markFailed(eventId, new IllegalStateException("Redis unavailable"));
        }

        assertThat(result).isNotNull();
        assertThat(result.attemptCount()).isEqualTo(TestProperties.OUTBOX.maxAttempts());
        assertThat(result.terminal()).isTrue();
        assertThat(event.getLastError()).isEqualTo("Redis unavailable");
    }
}