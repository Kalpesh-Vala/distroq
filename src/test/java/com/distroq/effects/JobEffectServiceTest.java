package com.distroq.effects;

import com.distroq.TestProperties;
import com.distroq.config.DistroqProperties;
import com.distroq.model.EffectStatus;
import com.distroq.model.JobEffect;
import com.distroq.repository.EffectCounterRepository;
import com.distroq.repository.JobEffectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The ledger without a database.
 *
 * <p>The stubbed {@code claim} inserts only when the key is absent, which is what
 * {@code INSERT ... ON CONFLICT DO NOTHING} does once PostgreSQL has serialised two callers on
 * the primary key. The genuinely concurrent case — two connections, one blocking on the other's
 * uncommitted insert — cannot be faked and is covered live in A11.
 */
class JobEffectServiceTest {

    private final JobEffectRepository ledger = mock(JobEffectRepository.class);
    private final EffectCounterRepository counters = mock(EffectCounterRepository.class);
    private final EffectMetrics metrics = new EffectMetrics();
    private final Map<String, JobEffect> rows = new HashMap<>();
    private final Map<String, Long> counterValues = new HashMap<>();

    private JobEffectService service;

    @BeforeEach
    void setUp() {
        service = new JobEffectService(ledger, counters, metrics, TestProperties.defaults());
        stub();
    }

    @Test
    void theFirstApplicationCreatesOneLedgerRowAndIncrementsOnce() {
        UUID jobId = UUID.randomUUID();

        EffectOutcome outcome = service.applyCounter(jobId, 1, "orders:daily");

        assertThat(outcome.newlyApplied()).isTrue();
        assertThat(outcome.status()).isEqualTo(EffectStatus.COMPLETED);
        assertThat(outcome.counterValue()).isEqualTo(1L);
        assertThat(outcome.effectKey()).isEqualTo(jobId + ":counter:orders:daily");
        assertThat(rows).hasSize(1);
        assertThat(counterValues).containsEntry("orders:daily", 1L);
    }

    @Test
    void aRepeatedEffectKeyDoesNotIncrementTheCounterAgain() {
        UUID jobId = UUID.randomUUID();
        service.applyCounter(jobId, 1, "orders:daily");

        EffectOutcome second = service.applyCounter(jobId, 2, "orders:daily");

        assertThat(second.newlyApplied()).isFalse();
        assertThat(second.status()).isEqualTo(EffectStatus.COMPLETED);
        assertThat(counterValues).containsEntry("orders:daily", 1L);
        assertThat(rows).hasSize(1);
    }

    @Test
    void aCompletedEffectReturnsThePreviousResultMetadata() {
        UUID jobId = UUID.randomUUID();
        EffectOutcome first = service.applyCounter(jobId, 1, "orders:daily");

        EffectOutcome second = service.applyCounter(jobId, 2, "orders:daily");

        assertThat(second.responseHash()).isEqualTo(first.responseHash());
        assertThat(second.counterValue()).isEqualTo(first.counterValue());
    }

    @Test
    void aDeduplicatedApplicationCountsAsAnIdempotencyHitRatherThanAnApplication() {
        UUID jobId = UUID.randomUUID();
        service.applyCounter(jobId, 1, "orders:daily");
        service.applyCounter(jobId, 2, "orders:daily");

        assertThat(metrics.applications()).isEqualTo(1);
        assertThat(metrics.deduplicationHits()).isEqualTo(1);
    }

    @Test
    void differentEffectKeysIncrementIndependently() {
        service.applyCounter(UUID.randomUUID(), 1, "orders:daily");
        service.applyCounter(UUID.randomUUID(), 1, "orders:daily");

        assertThat(counterValues).containsEntry("orders:daily", 2L);
        assertThat(rows).hasSize(2);
    }

    @Test
    void thePayloadIsNormalizedSoTheSameCounterCannotBeClaimedTwice() {
        UUID jobId = UUID.randomUUID();
        service.applyCounter(jobId, 1, "  Orders:Daily  ");

        EffectOutcome second = service.applyCounter(jobId, 2, "orders:daily");

        assertThat(second.newlyApplied()).isFalse();
        assertThat(counterValues).containsEntry("orders:daily", 1L);
    }

    @Test
    void aFailedEffectMayBeClaimedAgainUnderTheSameKeyOnOneRow() {
        UUID jobId = UUID.randomUUID();
        String key = EffectKeys.counter(jobId, "orders:daily");
        service.claim(key, jobId, 1, JobEffectService.COUNTER_EFFECT_TYPE);
        service.fail(key, "downstream refused");

        EffectOutcome retried = service.applyCounter(jobId, 2, "orders:daily");

        assertThat(retried.newlyApplied()).isTrue();
        assertThat(counterValues).containsEntry("orders:daily", 1L);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(key).getAttemptNumber()).isEqualTo(2);
    }

    /**
     * The one case the ledger refuses to guess at: STARTED means somebody claimed the key and
     * never came back, so the effect may or may not have happened.
     */
    @Test
    void aStartedEffectHeldBySomebodyElseIsNotAppliedASecondTime() {
        UUID jobId = UUID.randomUUID();
        service.claim(EffectKeys.counter(jobId, "orders:daily"), jobId, 1,
                JobEffectService.COUNTER_EFFECT_TYPE);

        assertThatThrownBy(() -> service.applyCounter(jobId, 2, "orders:daily"))
                .isInstanceOf(EffectInProgressException.class)
                .hasMessageContaining("STARTED");
        assertThat(counterValues).doesNotContainKey("orders:daily");
    }

    @Test
    void aSecondClaimOfACompletedKeyReportsTheExistingResultRatherThanTakingOwnership() {
        UUID jobId = UUID.randomUUID();
        String key = EffectKeys.counter(jobId, "orders:daily");
        service.applyCounter(jobId, 1, "orders:daily");

        JobEffectService.Claim claim =
                service.claim(key, jobId, 2, JobEffectService.COUNTER_EFFECT_TYPE);

        assertThat(claim.kind()).isEqualTo(JobEffectService.Claim.Kind.ALREADY_COMPLETED);
        assertThat(claim.responseHash()).isNotNull();
    }

    @Test
    void theResponseHashIsStableForTheSameIdentityAndResult() {
        String key = EffectKeys.counter(UUID.randomUUID(), "orders:daily");

        assertThat(ResponseHash.of(key + ":1")).isEqualTo(ResponseHash.of(key + ":1"));
        assertThat(ResponseHash.of(key + ":1")).isNotEqualTo(ResponseHash.of(key + ":2"));
        assertThat(ResponseHash.of(key + ":1")).hasSize(64);
    }

    @Test
    void noSensitiveResponseDataIsStoredAlongsideTheHash() {
        service.applyCounter(UUID.randomUUID(), 1, "orders:daily");

        JobEffect stored = rows.values().iterator().next();
        assertThat(stored.getResponseHash()).matches("[0-9a-f]{64}");
        assertThat(stored.getErrorMessage()).isNull();
        assertThat(stored.getStatus()).isEqualTo(EffectStatus.COMPLETED);
    }

    @Test
    void theStaleThresholdComesFromConfiguration() {
        JobEffectService configured = new JobEffectService(ledger, counters, metrics,
                TestProperties.of(new DistroqProperties.Effects(true, 5_000L, false)));

        assertThat(configured.staleBefore(Instant.parse("2026-01-01T00:00:10Z")))
                .isEqualTo(Instant.parse("2026-01-01T00:00:05Z"));
    }

    @Test
    void theCounterIsRefusedWhenEffectsAreTurnedOff() {
        JobEffectService off = new JobEffectService(ledger, counters, metrics,
                TestProperties.of(new DistroqProperties.Effects(false, 300_000L, false)));

        assertThatThrownBy(() -> off.applyCounter(UUID.randomUUID(), 1, "orders:daily"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("distroq.effects.enabled");
    }

    private void stub() {
        when(ledger.claim(anyString(), any(UUID.class), anyInt(), anyString(), any(Instant.class)))
                .thenAnswer(invocation -> {
                    String key = invocation.getArgument(0);
                    if (rows.containsKey(key)) {
                        return 0;
                    }
                    rows.put(key, JobEffects.started(key, invocation.getArgument(1),
                            invocation.getArgument(2), invocation.getArgument(3),
                            invocation.getArgument(4)));
                    return 1;
                });
        when(ledger.lockByKey(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(rows.get(invocation.getArgument(0))));
        when(counters.increment(anyString(), any(Instant.class)))
                .thenAnswer(invocation -> {
                    counterValues.merge(invocation.getArgument(0), 1L, Long::sum);
                    return 1;
                });
        when(counters.currentValue(anyString()))
                .thenAnswer(invocation -> counterValues.get(invocation.getArgument(0)));
    }
}
