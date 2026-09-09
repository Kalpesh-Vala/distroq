package com.distroq.model;

import com.distroq.outbox.OutboxEventType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxEventTest {

    private static final int MAX_ATTEMPTS = 3;

    @Test
    void newEventStartsUnpublishedAndUnattempted() {
        OutboxEvent event = event();

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.getLockedUntil()).isNull();
        assertThat(event.getAttemptCount()).isZero();
        assertThat(event.getLastError()).isNull();
        assertThat(event.getOperatorRetryCount()).isZero();
        assertThat(event.getTerminalFailedAt()).isNull();
    }

    @Test
    void claimingMovesTheEventToPublishing() {
        OutboxEvent event = event();

        event.claimUntil(Instant.now().plusSeconds(30));

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHING);
    }

    @Test
    void successfulPublicationClearsTheLeaseAndMarksPublished() {
        OutboxEvent event = event();
        event.claimUntil(Instant.now().plusSeconds(30));
        Instant publishedAt = Instant.now();

        event.markPublished(publishedAt);

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(event.getPublishedAt()).isEqualTo(publishedAt);
        assertThat(event.getLockedUntil()).isNull();
    }

    @Test
    void failureIncrementsAttemptsRetainsTheEventAndClearsTheLease() {
        OutboxEvent event = event();
        event.claimUntil(Instant.now().plusSeconds(30));

        boolean terminal = event.markFailed("redis unavailable", MAX_ATTEMPTS);

        assertThat(terminal).isFalse();
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.getAttemptCount()).isEqualTo(1);
        assertThat(event.getLastError()).isEqualTo("redis unavailable");
        assertThat(event.getLockedUntil()).isNull();
    }

    @Test
    void reachingTheCeilingMakesTheEventTerminalAndTimestampsIt() {
        OutboxEvent event = failToTerminal(event());

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(event.isTerminal()).isTrue();
        assertThat(event.getTerminalFailedAt()).isNotNull();
        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.getLastError()).isEqualTo("attempt " + MAX_ATTEMPTS);
    }

    @Test
    void operatorRetryReturnsTheEventToPendingWithoutChangingItsIdentity() {
        OutboxEvent event = failToTerminal(event());
        UUID originalId = event.getId();
        int attemptsBefore = event.getAttemptCount();
        Instant retriedAt = Instant.now();

        event.operatorRetry("Redis was restored after maintenance", retriedAt);

        assertThat(event.getId()).isEqualTo(originalId);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getLockedUntil()).isNull();
        assertThat(event.getAvailableAt()).isEqualTo(retriedAt);
        assertThat(event.getAttemptCount()).isEqualTo(attemptsBefore);
        assertThat(event.getOperatorRetryCount()).isEqualTo(1);
        assertThat(event.getLastOperatorRetryAt()).isEqualTo(retriedAt);
        assertThat(event.getLastOperatorReason()).isEqualTo("Redis was restored after maintenance");
    }

    @Test
    void operatorRetryGrantsAFreshBudgetWithoutErasingHistory() {
        OutboxEvent event = failToTerminal(event());
        event.operatorRetry("first repair", Instant.now());

        assertThat(event.terminalCeiling(MAX_ATTEMPTS)).isEqualTo(MAX_ATTEMPTS * 2);

        boolean terminalAgain = false;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            terminalAgain = event.markFailed("attempt after repair", MAX_ATTEMPTS);
        }

        assertThat(terminalAgain).isTrue();
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(event.getAttemptCount()).isEqualTo(MAX_ATTEMPTS * 2);
        assertThat(event.getOperatorRetryCount()).isEqualTo(1);
        assertThat(event.getLastOperatorReason()).isEqualTo("first repair");
    }

    @Test
    void aSecondOperatorRetryIsCountedAndKeepsTheLatestReason() {
        OutboxEvent event = failToTerminal(event());
        event.operatorRetry("first repair", Instant.now());
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            event.markFailed("still failing", MAX_ATTEMPTS);
        }

        event.operatorRetry("second repair", Instant.now());

        assertThat(event.getOperatorRetryCount()).isEqualTo(2);
        assertThat(event.getLastOperatorReason()).isEqualTo("second repair");
        assertThat(event.terminalCeiling(MAX_ATTEMPTS)).isEqualTo(MAX_ATTEMPTS * 3);
    }

    @Test
    void onlyATerminalEventCanBeRetriedByAnOperator() {
        OutboxEvent pending = event();

        assertThatThrownBy(() -> pending.operatorRetry("too early", Instant.now()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PENDING");
    }

    @Test
    void releasingAnExpiredLockReturnsAnUnpublishedEventToPending() {
        OutboxEvent event = event();
        event.claimUntil(Instant.now().minusSeconds(1));

        event.releaseExpiredLock();

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getLockedUntil()).isNull();
    }

    private OutboxEvent failToTerminal(OutboxEvent event) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            event.markFailed("attempt " + attempt, MAX_ATTEMPTS);
        }
        return event;
    }

    private OutboxEvent event() {
        UUID id = UUID.randomUUID();
        return OutboxEvent.create(id, UUID.randomUUID(), OutboxEventType.ENQUEUE_SUBMIT,
                "{\"eventId\":\"" + id + "\"}", Instant.now());
    }
}