package com.distroq.outbox;

import com.distroq.model.OutboxEvent;
import com.distroq.model.OutboxStatus;
import com.distroq.model.ReliabilityActionType;
import com.distroq.reliability.ReliabilityAuditService;
import com.distroq.repository.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxOperatorServiceTest {

    private static final int MAX_ATTEMPTS = 3;

    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    private final ReliabilityAuditService audit = mock(ReliabilityAuditService.class);
    private final OutboxOperatorService service = new OutboxOperatorService(repository, audit);

    @Test
    void retryingATerminalEventReturnsItToPendingWithoutChangingItsIdentity() {
        OutboxEvent event = terminal();
        when(repository.findById(event.getId())).thenReturn(Optional.of(event));

        OutboxEvent retried = service.retry(event.getId(), "Redis is back", "alice", "maintenance");

        assertThat(retried.getId()).isEqualTo(event.getId());
        assertThat(retried.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(retried.getOperatorRetryCount()).isEqualTo(1);
        assertThat(retried.getLastOperatorReason()).isEqualTo("Redis is back");
        assertThat(retried.getAttemptCount()).isEqualTo(MAX_ATTEMPTS);
    }

    @Test
    void theRetryIsAuditedWithBothReasonsAndTheActor() {
        OutboxEvent event = terminal();
        when(repository.findById(event.getId())).thenReturn(Optional.of(event));

        service.retry(event.getId(), "Redis is back", "alice", "post-maintenance sweep");

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(audit).record(eq(ReliabilityActionType.OUTBOX_RETRY), eq("OutboxEvent"),
                eq(event.getId()), reason.capture(), eq("alice"), anyString(), anyString());
        assertThat(reason.getValue())
                .contains("Redis is back")
                .contains("X-Admin-Reason: post-maintenance sweep");
    }

    @Test
    void theAuditStatesDoNotContainThePayload() {
        OutboxEvent event = terminal();
        when(repository.findById(event.getId())).thenReturn(Optional.of(event));

        service.retry(event.getId(), "Redis is back", "alice", "maintenance");

        ArgumentCaptor<String> before = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> after = ArgumentCaptor.forClass(String.class);
        verify(audit).record(any(), anyString(), any(UUID.class), anyString(), anyString(),
                before.capture(), after.capture());
        assertThat(before.getValue()).doesNotContain(event.getPayload());
        assertThat(after.getValue()).doesNotContain(event.getPayload());
    }

    @Test
    void anUnknownEventIsNotFound() {
        UUID missing = UUID.randomUUID();
        when(repository.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.retry(missing, "why not", "alice", "checking"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(failure -> ((ResponseStatusException) failure).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aPendingEventCannotBeRetriedAndIsNotAudited() {
        OutboxEvent event = event();
        when(repository.findById(event.getId())).thenReturn(Optional.of(event));

        assertThatThrownBy(() -> service.retry(event.getId(), "too soon", "alice", "checking"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(failure -> ((ResponseStatusException) failure).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        verify(audit, never()).record(any(), anyString(), any(), anyString(), anyString(),
                anyString(), anyString());
    }

    @Test
    void aPublishedEventCannotBeRetried() {
        OutboxEvent event = event();
        event.claimUntil(Instant.now().plusSeconds(30));
        event.markPublished(Instant.now());
        when(repository.findById(event.getId())).thenReturn(Optional.of(event));

        assertThatThrownBy(() -> service.retry(event.getId(), "again", "alice", "checking"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("PUBLISHED");
    }

    @Test
    void aSecondOperatorRetryIsRecordedSeparately() {
        OutboxEvent event = terminal();
        when(repository.findById(event.getId())).thenReturn(Optional.of(event));
        service.retry(event.getId(), "first", "alice", "first sweep");
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            event.markFailed("still down", MAX_ATTEMPTS);
        }

        service.retry(event.getId(), "second", "bob", "second sweep");

        assertThat(event.getOperatorRetryCount()).isEqualTo(2);
        assertThat(event.getLastOperatorReason()).isEqualTo("second");
        verify(audit).record(eq(ReliabilityActionType.OUTBOX_RETRY), anyString(), eq(event.getId()),
                anyString(), eq("bob"), anyString(), anyString());
    }

    private static OutboxEvent terminal() {
        OutboxEvent event = event();
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            event.markFailed("redis unavailable", MAX_ATTEMPTS);
        }
        return event;
    }

    private static OutboxEvent event() {
        UUID id = UUID.randomUUID();
        return OutboxEvent.create(id, UUID.randomUUID(), OutboxEventType.ENQUEUE_SUBMIT,
                "{\"eventId\":\"" + id + "\",\"secret\":\"do-not-log-me\"}", Instant.now());
    }
}
