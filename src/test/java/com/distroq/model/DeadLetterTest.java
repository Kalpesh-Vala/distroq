package com.distroq.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DeadLetterTest {

    @Test
    void ofStartsUnreplayedWithNoReplayHistory() {
        DeadLetter deadLetter = DeadLetter.of(UUID.randomUUID(), "boom");

        assertThat(deadLetter.getFinalError()).isEqualTo("boom");
        assertThat(deadLetter.getMovedAt()).isNotNull();
        assertThat(deadLetter.isReplayed()).isFalse();
        assertThat(deadLetter.getReplayedAt()).isNull();
        assertThat(deadLetter.getReplayCount()).isZero();
    }

    @Test
    void markReplayedIncrementsCountAndKeepsMovedAt() {
        DeadLetter deadLetter = DeadLetter.of(UUID.randomUUID(), "boom");
        Instant movedAt = deadLetter.getMovedAt();

        deadLetter.markReplayed();

        assertThat(deadLetter.isReplayed()).isTrue();
        assertThat(deadLetter.getReplayedAt()).isNotNull();
        assertThat(deadLetter.getReplayCount()).isEqualTo(1);
        assertThat(deadLetter.getMovedAt()).isEqualTo(movedAt);
    }

    @Test
    void reDeadLetteringPreservesReplayCountAndRefreshesTheFailure() throws InterruptedException {
        DeadLetter deadLetter = DeadLetter.of(UUID.randomUUID(), "boom");
        deadLetter.markReplayed();
        Instant firstMovedAt = deadLetter.getMovedAt();
        Instant replayedAt = deadLetter.getReplayedAt();
        Thread.sleep(2);

        deadLetter.markDeadLetteredAgain("boom again");

        assertThat(deadLetter.getReplayCount()).isEqualTo(1);
        assertThat(deadLetter.isReplayed()).isFalse();
        assertThat(deadLetter.getFinalError()).isEqualTo("boom again");
        assertThat(deadLetter.getMovedAt()).isAfter(firstMovedAt);
        assertThat(deadLetter.getReplayedAt()).isEqualTo(replayedAt);
    }

    @Test
    void repeatedReplaysAccumulate() {
        DeadLetter deadLetter = DeadLetter.of(UUID.randomUUID(), "boom");

        deadLetter.markReplayed();
        deadLetter.markDeadLetteredAgain("boom again");
        deadLetter.markReplayed();

        assertThat(deadLetter.getReplayCount()).isEqualTo(2);
        assertThat(deadLetter.isReplayed()).isTrue();
    }
}
