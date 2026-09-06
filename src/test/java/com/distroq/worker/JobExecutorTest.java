package com.distroq.worker;

import com.distroq.model.Job;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobExecutorTest {

    private final JobExecutor executor = new JobExecutor();

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
}
