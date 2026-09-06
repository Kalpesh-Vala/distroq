package com.distroq.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class JobTest {

    @Test
    void createStartsQueuedWithNoAttempts() {
        Job job = Job.create("sleep", "1000", 3);

        assertThat(job.getId()).isNotNull();
        assertThat(job.getType()).isEqualTo("sleep");
        assertThat(job.getPayload()).isEqualTo("1000");
        assertThat(job.getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(job.getAttemptCount()).isZero();
        assertThat(job.getMaxAttempts()).isEqualTo(3);
        assertThat(job.getCreatedAt()).isNotNull();
        assertThat(job.getUpdatedAt()).isNotNull();
        assertThat(job.getStartedAt()).isNull();
        assertThat(job.getFinishedAt()).isNull();
        assertThat(job.getNextAttemptAt()).isNull();
        assertThat(job.getErrorMessage()).isNull();
    }

    @Test
    void markRunningIncrementsAttemptsAndSetsStartedAt() {
        Job job = Job.create("sleep", "1000", 3);

        job.markRunning();

        assertThat(job.getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(job.getAttemptCount()).isEqualTo(1);
        assertThat(job.getStartedAt()).isNotNull();
        assertThat(job.getFinishedAt()).isNull();
    }

    @Test
    void markSucceededSetsTerminalStateAndFinishedAt() {
        Job job = Job.create("sleep", "1000", 3);
        job.markRunning();

        job.markSucceeded();

        assertThat(job.getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(job.getFinishedAt()).isNotNull();
        assertThat(job.getFinishedAt()).isAfterOrEqualTo(job.getStartedAt());
        assertThat(job.getErrorMessage()).isNull();
    }

    @Test
    void markFailedRecordsErrorAndFinishedAt() {
        Job job = Job.create("always_fail", "", 3);
        job.markRunning();

        job.markFailed("boom");

        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.getErrorMessage()).isEqualTo("boom");
        assertThat(job.getFinishedAt()).isNotNull();
        assertThat(job.getNextAttemptAt()).isNull();
        assertThat(job.getAttemptCount()).isEqualTo(1);
    }

    @Test
    void hasAttemptsRemainingUntilAttemptCountReachesMaxAttempts() {
        Job job = Job.create("always_fail", "", 3);

        assertThat(job.hasAttemptsRemaining()).isTrue();
        job.markRunning();
        assertThat(job.hasAttemptsRemaining()).isTrue();
        job.markRunning();
        assertThat(job.hasAttemptsRemaining()).isTrue();
        job.markRunning();
        assertThat(job.getAttemptCount()).isEqualTo(3);
        assertThat(job.hasAttemptsRemaining()).isFalse();
    }

    @Test
    void singleAttemptJobHasNothingRemainingAfterItsFirstRun() {
        Job job = Job.create("always_fail", "", 1);
        job.markRunning();

        assertThat(job.hasAttemptsRemaining()).isFalse();
    }

    @Test
    void markRetryingIsNotTerminal() {
        Job job = Job.create("always_fail", "", 3);
        job.markRunning();
        Instant dueAt = Instant.now().plusSeconds(1);

        job.markRetrying("boom", dueAt);

        assertThat(job.getStatus()).isEqualTo(JobStatus.RETRYING);
        assertThat(job.getErrorMessage()).isEqualTo("boom");
        assertThat(job.getNextAttemptAt()).isEqualTo(dueAt);
        assertThat(job.getFinishedAt()).isNull();
    }

    @Test
    void markRunningClearsTheScheduledNextAttempt() {
        Job job = Job.create("always_fail", "", 3);
        job.markRunning();
        job.markRetrying("boom", Instant.now().plusSeconds(1));

        job.markRunning();

        assertThat(job.getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(job.getNextAttemptAt()).isNull();
        assertThat(job.getAttemptCount()).isEqualTo(2);
    }

    @Test
    void markFailedAfterRetryingClearsNextAttemptAndStaysTerminal() {
        Job job = Job.create("always_fail", "", 2);
        job.markRunning();
        job.markRetrying("boom", Instant.now().plusSeconds(1));
        job.markRunning();

        job.markFailed("boom again");

        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.getNextAttemptAt()).isNull();
        assertThat(job.getFinishedAt()).isNotNull();
    }

    @Test
    void markDeadLetteredIsTerminalAndRetainsTheError() {
        Job job = Job.create("always_fail", "", 2);
        job.markRunning();
        job.markRetrying("boom", Instant.now().plusSeconds(1));
        job.markRunning();

        job.markDeadLettered("boom again");

        assertThat(job.getStatus()).isEqualTo(JobStatus.DEAD_LETTERED);
        assertThat(job.getErrorMessage()).isEqualTo("boom again");
        assertThat(job.getFinishedAt()).isNotNull();
        assertThat(job.getNextAttemptAt()).isNull();
        assertThat(job.getAttemptCount()).isEqualTo(2);
        assertThat(job.hasAttemptsRemaining()).isFalse();
    }

    @Test
    void prepareForReplayContinuesHistoryRatherThanResettingIt() {
        Job job = Job.create("always_fail", "", 3);
        job.markRunning();
        job.markRunning();
        job.markRunning();
        job.markDeadLettered("exhausted");

        job.prepareForReplay(3);

        assertThat(job.getAttemptCount()).isEqualTo(3);
        assertThat(job.getMaxAttempts()).isEqualTo(6);
        assertThat(job.getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(job.getErrorMessage()).isNull();
        assertThat(job.getFinishedAt()).isNull();
        assertThat(job.getNextAttemptAt()).isNull();
        // if this is false the replayed job is out of budget before it runs a single attempt
        assertThat(job.hasAttemptsRemaining()).isTrue();
    }

    @Test
    void replayedJobRunsAsTheNextAttemptNumber() {
        Job job = Job.create("fail_until_flagged", "", 2);
        job.markRunning();
        job.markRunning();
        job.markDeadLettered("exhausted");
        job.prepareForReplay(3);

        job.markRunning();

        assertThat(job.getAttemptCount()).isEqualTo(3);
        assertThat(job.getMaxAttempts()).isEqualTo(5);
    }

    @Test
    void createStoresEachTierAsGiven() {
        for (Priority priority : Priority.values()) {
            Job job = Job.create("sleep", "100", 3, priority);

            assertThat(job.getPriority()).as(priority.name()).isEqualTo(priority);
        }
    }

    @Test
    void createResolvesANullPriorityToNormalRatherThanThrowing() {
        Job job = Job.create("sleep", "100", 3, null);

        assertThat(job.getPriority()).isEqualTo(Priority.NORMAL);
    }

    @Test
    void theThreeArgumentCreateStillMeansNormal() {
        // the v0.3 call shape must keep behaving exactly as it did, which is what every other
        // test in this class is quietly asserting
        assertThat(Job.create("sleep", "100", 3).getPriority()).isEqualTo(Priority.NORMAL);
    }

    @Test
    void priorityIsUnchangedByEveryStateTransitionIncludingReplay() {
        Job job = Job.create("always_fail", "", 2, Priority.HIGH);

        job.markRunning();
        job.markRetrying("boom", Instant.now().plusSeconds(1));
        job.markRunning();
        job.markDeadLettered("exhausted");
        job.prepareForReplay(3);
        job.markRunning();
        job.markSucceeded();

        // a job that comes back from a retry or a replay at a different tier is a silent
        // correctness bug that no status assertion would ever catch
        assertThat(job.getPriority()).isEqualTo(Priority.HIGH);
    }
}
