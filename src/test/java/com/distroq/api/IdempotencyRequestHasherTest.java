package com.distroq.api;

import com.distroq.model.Priority;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyRequestHasherTest {

    private final IdempotencyRequestHasher hasher = new IdempotencyRequestHasher(
            JsonMapper.builder().findAndAddModules().build());

    @Test
    void equivalentTimestampOffsetsHaveTheSameHash() {
        assertThat(hash("500", Priority.HIGH, 3, Instant.parse("2026-09-07T15:30:00Z")))
                .isEqualTo(hash("500", Priority.HIGH, 3,
                        Instant.parse("2026-09-07T17:30:00+02:00")));
    }

    @Test
    void canonicalSerializationIsStableAcrossRepeatedCalls() {
        assertThat(hash("500", Priority.NORMAL, 3, null))
                .isEqualTo(hash("500", Priority.NORMAL, 3, null));
    }

    @Test
    void differentPayloadsHaveDifferentHashes() {
        assertThat(hash("500", Priority.NORMAL, 3, null))
                .isNotEqualTo(hash("9999", Priority.NORMAL, 3, null));
    }

    @Test
    void differentPrioritiesHaveDifferentHashes() {
        assertThat(hash("500", Priority.HIGH, 3, null))
                .isNotEqualTo(hash("500", Priority.LOW, 3, null));
    }

    @Test
    void differentAttemptBudgetsHaveDifferentHashes() {
        assertThat(hash("500", Priority.NORMAL, 3, null))
                .isNotEqualTo(hash("500", Priority.NORMAL, 4, null));
    }

    @Test
    void scheduledAndImmediateRequestsHaveDifferentHashes() {
        assertThat(hash("500", Priority.NORMAL, 3, null))
                .isNotEqualTo(hash("500", Priority.NORMAL, 3,
                        Instant.parse("2026-09-07T15:30:00Z")));
    }

    private String hash(String payload, Priority priority, int maxAttempts, Instant scheduledAt) {
        return hasher.hash("sleep", payload, maxAttempts, priority, scheduledAt);
    }
}