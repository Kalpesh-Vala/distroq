package com.distroq.worker;

import com.distroq.model.Job;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JobExecutorTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final JobExecutor executor = new JobExecutor(redis);

    @Test
    void sleepHonoursPayloadDuration() throws Exception {
        long start = System.nanoTime();

        executor.execute(Job.create("sleep", "300", 3));

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsedMs).isGreaterThanOrEqualTo(280);
    }

    @Test
    void sleepFallsBackToDefaultOnUnparseablePayload() throws Exception {
        long start = System.nanoTime();

        executor.execute(Job.create("sleep", "not-a-number", 3));

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsedMs).isGreaterThanOrEqualTo(950);
    }

    @Test
    void sleepFallsBackToDefaultOnBlankPayload() throws Exception {
        long start = System.nanoTime();

        executor.execute(Job.create("sleep", "   ", 3));

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsedMs).isGreaterThanOrEqualTo(950);
    }

    @Test
    void alwaysFailThrows() {
        assertThatThrownBy(() -> executor.execute(Job.create("always_fail", "", 3)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("always_fail");
    }

    @Test
    void unknownTypeThrowsIllegalArgumentNamingTheType() {
        assertThatThrownBy(() -> executor.execute(Job.create("nonsense", "", 3)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nonsense");
    }

    @Test
    void failNTimesFailsTheFirstTwoAttemptsAndSucceedsOnTheThird() {
        Job job = Job.create("fail_n_times", "2", 5);

        job.markRunning();
        assertThatThrownBy(() -> executor.execute(job))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fail_n_times");

        job.markRunning();
        assertThatThrownBy(() -> executor.execute(job))
                .isInstanceOf(IllegalStateException.class);

        job.markRunning();
        assertThatCode(() -> executor.execute(job)).doesNotThrowAnyException();
    }

    @Test
    void failNTimesDefaultsToTwoOnUnparseableOrAbsentPayload() {
        for (String payload : new String[] {null, "", "  ", "not-a-number"}) {
            Job job = Job.create("fail_n_times", payload, 5);

            job.markRunning();
            assertThatThrownBy(() -> executor.execute(job)).isInstanceOf(IllegalStateException.class);
            job.markRunning();
            assertThatThrownBy(() -> executor.execute(job)).isInstanceOf(IllegalStateException.class);
            job.markRunning();
            assertThatCode(() -> executor.execute(job)).doesNotThrowAnyException();
        }
    }

    @Test
    void failNTimesWithZeroSucceedsImmediately() {
        Job job = Job.create("fail_n_times", "0", 3);
        job.markRunning();

        assertThatCode(() -> executor.execute(job)).doesNotThrowAnyException();
    }

    @Test
    void failUntilFlaggedSetsItsOwnFlagOnTheFirstAttemptAndFails() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(redis.hasKey(anyString())).thenReturn(true);
        Job job = Job.create("fail_until_flagged", "", 2);
        job.markRunning();

        assertThatThrownBy(() -> executor.execute(job))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("distroq:test:flag:" + job.getId());

        verify(valueOps).set("distroq:test:flag:" + job.getId(), "set");
    }

    @Test
    void failUntilFlaggedSucceedsOnceTheFlagIsCleared() {
        when(redis.hasKey(anyString())).thenReturn(false);
        Job job = Job.create("fail_until_flagged", "", 5);
        job.markRunning();
        job.markRunning();

        assertThatCode(() -> executor.execute(job)).doesNotThrowAnyException();

        // the flag is only ever set on the first attempt, so a replay does not re-break the job
        verify(redis, never()).opsForValue();
    }
}
