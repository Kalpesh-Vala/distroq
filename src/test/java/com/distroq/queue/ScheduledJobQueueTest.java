package com.distroq.queue;

import com.distroq.TestProperties;
import com.distroq.model.Priority;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The scheduled set's own behaviour: what a member looks like, what a duplicate does, and what the
 * depth numbers count.
 *
 * <p>The promotion itself is one line of delegation here and one Lua script in
 * {@link DueSetPromoter}; what the script does to a live sorted set is verified against a real
 * Redis in the README's A2, A3 and A7 checks, because a mock cannot tell you whether
 * {@code ZRANGEBYSCORE} respects a score.
 */
class ScheduledJobQueueTest {

    private static final String KEY = "distroq:jobs:scheduled";
    private static final UUID JOB_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private StringRedisTemplate redis;
    private ZSetOperations<String, String> zset;
    private DueSetPromoter promoter;
    private ScheduledJobQueue queue;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        zset = mock(ZSetOperations.class);
        promoter = mock(DueSetPromoter.class);
        when(redis.opsForZSet()).thenReturn(zset);
        when(zset.addIfAbsent(anyString(), anyString(), anyDouble())).thenReturn(true);
        queue = new ScheduledJobQueue(redis, promoter, TestProperties.defaults());
    }

    @Test
    void schedulingWritesTheTierTaggedMemberScoredByEpochMillis() {
        Instant dueAt = Instant.parse("2026-09-07T15:30:00Z");

        queue.schedule(JOB_ID, Priority.HIGH, dueAt);

        verify(zset).addIfAbsent(KEY, "HIGH:" + JOB_ID, (double) dueAt.toEpochMilli());
    }

    @Test
    void twoSpellingsOfTheSameInstantProduceTheSameScore() {
        // the offset is gone by the time it reaches here, so this is really an assertion that
        // nothing downstream of parsing can reintroduce a timezone
        queue.schedule(JOB_ID, Priority.NORMAL, Instant.parse("2026-09-07T15:30:00Z"));
        queue.schedule(JOB_ID, Priority.NORMAL, Instant.parse("2026-09-07T17:30:00+02:00"));

        ArgumentCaptor<Double> scores = ArgumentCaptor.forClass(Double.class);
        verify(zset, times(2)).addIfAbsent(eq(KEY), anyString(), scores.capture());
        assertThat(scores.getAllValues().get(0)).isEqualTo(scores.getAllValues().get(1));
    }

    @Test
    void schedulingUsesZaddNxSoADuplicateNeverMovesAnExistingDueTime() {
        // rescheduling is out of scope in v0.6, so the only thing that can produce a second call
        // is a repeat of a request that was already accepted. Plain ZADD would silently move the
        // job's execution time; NX makes the duplicate an idempotent no-op
        when(zset.addIfAbsent(anyString(), anyString(), anyDouble())).thenReturn(false);
        Instant dueAt = Instant.parse("2026-09-07T15:30:00Z");

        queue.schedule(JOB_ID, Priority.LOW, dueAt);

        verify(zset).addIfAbsent(KEY, "LOW:" + JOB_ID, (double) dueAt.toEpochMilli());
        // and nothing else: no repair, no second write, no removal
        verify(zset, never()).add(anyString(), anyString(), anyDouble());
        verify(zset, never()).remove(anyString(), any());
    }

    @Test
    void promotionDelegatesToTheSharedScriptWithTheScheduledKeyAndSource() {
        Instant now = Instant.parse("2026-09-07T15:30:00Z");
        when(promoter.promote(KEY, now, 50, EnqueueSource.SCHEDULED)).thenReturn(7);

        assertThat(queue.promoteDueJobs(now, 50)).isEqualTo(7);
        verify(promoter).promote(KEY, now, 50, EnqueueSource.SCHEDULED);
    }

    @Test
    void promotionNeverUsesTheRetrySourceOrTheRetryKey() {
        queue.promoteDueJobs(Instant.now(), 100);

        verify(promoter, never()).promote(eq("distroq:jobs:delayed"), any(), anyInt(), any());
        verify(promoter, never()).promote(anyString(), any(), anyInt(), eq(EnqueueSource.RETRY));
    }

    @Test
    void depthIsTheCardinalityOfTheScheduledSetOnly() {
        when(zset.zCard(KEY)).thenReturn(4L);

        assertThat(queue.scheduledDepth()).isEqualTo(4L);
    }

    @Test
    void anAbsentKeyReportsZeroDepthRatherThanFailing() {
        when(zset.zCard(KEY)).thenReturn(null);

        assertThat(queue.scheduledDepth()).isZero();
    }

    @Test
    void depthByPriorityCountsMembersByTheTierEncodedInTheMember() {
        when(zset.range(KEY, 0, -1)).thenReturn(members(
                "HIGH:" + UUID.randomUUID(),
                "HIGH:" + UUID.randomUUID(),
                "LOW:" + UUID.randomUUID()));

        assertThat(queue.scheduledDepthByPriority())
                .containsExactly(
                        Map.entry(Priority.HIGH, 2L),
                        Map.entry(Priority.NORMAL, 0L),
                        Map.entry(Priority.LOW, 1L));
    }

    @Test
    void everyTierAppearsEvenWhenTheSetIsEmpty() {
        when(zset.range(KEY, 0, -1)).thenReturn(Set.of());

        assertThat(queue.scheduledDepthByPriority())
                .containsExactly(
                        Map.entry(Priority.HIGH, 0L),
                        Map.entry(Priority.NORMAL, 0L),
                        Map.entry(Priority.LOW, 0L));
    }

    @Test
    void anAbsentKeyReportsEveryTierAtZero() {
        when(zset.range(KEY, 0, -1)).thenReturn(null);

        assertThat(queue.scheduledDepthByPriority()).containsValues(0L, 0L, 0L);
    }

    @Test
    void anUnreadableMemberIsExcludedRatherThanCrashingTheMetricsCall() {
        // a member written by hand or by a future version must not be able to break /api/metrics
        when(zset.range(KEY, 0, -1)).thenReturn(members(
                "NORMAL:" + UUID.randomUUID(),
                "URGENT:" + UUID.randomUUID(),
                UUID.randomUUID().toString(),
                "HIGH:not-a-uuid"));

        assertThat(queue.scheduledDepthByPriority())
                .containsExactly(
                        Map.entry(Priority.HIGH, 0L),
                        Map.entry(Priority.NORMAL, 1L),
                        Map.entry(Priority.LOW, 0L));
    }

    private static Set<String> members(String... values) {
        return new LinkedHashSet<>(Arrays.asList(values));
    }
}
