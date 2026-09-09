package com.distroq.effects;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EffectKeysTest {

    private static final UUID JOB_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    void theCounterKeyIsIndependentOfTheAttemptNumber() {
        assertThat(EffectKeys.counter(JOB_ID, "orders:daily"))
                .isEqualTo(JOB_ID + ":counter:orders:daily");
    }

    @Test
    void thePerAttemptKeyIsNotTheCounterKey() {
        assertThat(EffectKeys.perAttempt(JOB_ID, 2)).isEqualTo(JOB_ID + ":attempt:2");
        assertThat(EffectKeys.perAttempt(JOB_ID, 2))
                .isNotEqualTo(EffectKeys.counter(JOB_ID, "orders:daily"));
    }

    @Test
    void normalizationFoldsCaseWhitespaceAndPadding() {
        assertThat(EffectKeys.normalize("  Orders:Daily ")).isEqualTo("orders:daily");
        assertThat(EffectKeys.normalize("orders  daily")).isEqualTo("orders-daily");
    }

    @Test
    void anAbsentPayloadNamesTheDefaultCounterRatherThanFailing() {
        assertThat(EffectKeys.normalize(null)).isEqualTo("default");
        assertThat(EffectKeys.normalize("   ")).isEqualTo("default");
    }

    /** The key column is 255 characters, and the job UUID plus separator already spends 45. */
    @Test
    void aLongPayloadIsTruncatedSoTheKeyStillFitsItsColumn() {
        String key = EffectKeys.counter(JOB_ID, EffectKeys.normalize("x".repeat(500)));

        assertThat(key.length()).isLessThanOrEqualTo(255);
    }
}
