package com.distroq.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JobTest {

    @Test
    void createStartsQueuedWithNoAttempts() {
        Job job = Job.create("sleep", "1000");

        assertThat(job.getId()).isNotNull();
        assertThat(job.getType()).isEqualTo("sleep");
        assertThat(job.getPayload()).isEqualTo("1000");
        assertThat(job.getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(job.getAttemptCount()).isZero();
        assertThat(job.getCreatedAt()).isNotNull();
        assertThat(job.getUpdatedAt()).isNotNull();
        assertThat(job.getStartedAt()).isNull();
        assertThat(job.getFinishedAt()).isNull();
        assertThat(job.getErrorMessage()).isNull();
    }

    @Test
    void markRunningIncrementsAttemptsAndSetsStartedAt() {
        Job job = Job.create("sleep", "1000");

        job.markRunning();

        assertThat(job.getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(job.getAttemptCount()).isEqualTo(1);
        assertThat(job.getStartedAt()).isNotNull();
        assertThat(job.getFinishedAt()).isNull();
    }

    @Test
    void markSucceededSetsTerminalStateAndFinishedAt() {
        Job job = Job.create("sleep", "1000");
        job.markRunning();

        job.markSucceeded();

        assertThat(job.getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(job.getFinishedAt()).isNotNull();
        assertThat(job.getFinishedAt()).isAfterOrEqualTo(job.getStartedAt());
        assertThat(job.getErrorMessage()).isNull();
    }

    @Test
    void markFailedRecordsErrorAndFinishedAt() {
        Job job = Job.create("always_fail", "");
        job.markRunning();

        job.markFailed("boom");

        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.getErrorMessage()).isEqualTo("boom");
        assertThat(job.getFinishedAt()).isNotNull();
        assertThat(job.getAttemptCount()).isEqualTo(1);
    }
}
