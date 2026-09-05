package com.distroq.worker;

import com.distroq.model.Job;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobExecutorTest {

    private final JobExecutor executor = new JobExecutor();

    @Test
    void sleepHonoursPayloadDuration() throws Exception {
        long start = System.nanoTime();

        executor.execute(Job.create("sleep", "300"));

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsedMs).isGreaterThanOrEqualTo(280);
    }

    @Test
    void sleepFallsBackToDefaultOnUnparseablePayload() throws Exception {
        long start = System.nanoTime();

        executor.execute(Job.create("sleep", "not-a-number"));

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsedMs).isGreaterThanOrEqualTo(950);
    }

    @Test
    void sleepFallsBackToDefaultOnBlankPayload() throws Exception {
        long start = System.nanoTime();

        executor.execute(Job.create("sleep", "   "));

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsedMs).isGreaterThanOrEqualTo(950);
    }

    @Test
    void alwaysFailThrows() {
        assertThatThrownBy(() -> executor.execute(Job.create("always_fail", "")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("always_fail");
    }

    @Test
    void unknownTypeThrowsIllegalArgumentNamingTheType() {
        assertThatThrownBy(() -> executor.execute(Job.create("nonsense", "")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nonsense");
    }
}
