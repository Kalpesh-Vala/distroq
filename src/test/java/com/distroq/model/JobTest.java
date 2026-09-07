package com.distroq.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    // ---- v0.6: user-requested execution times ----

    @Test
    void aFutureTimestampStartsTheJobScheduledRatherThanQueued() {
        Instant scheduledAt = Instant.now().plusSeconds(60);

        Job job = Job.create("sleep", "500", 3, Priority.HIGH, scheduledAt);

        assertThat(job.getStatus()).isEqualTo(JobStatus.SCHEDULED);
        assertThat(job.isScheduled()).isTrue();
        assertThat(job.getScheduledAt()).isEqualTo(scheduledAt);
        assertThat(job.getAttemptCount()).isZero();
        assertThat(job.getStartedAt()).isNull();
    }

    @Test
    void aNullTimestampStartsTheJobQueuedWithNoScheduleAtAll() {
        Job job = Job.create("sleep", "500", 3, Priority.NORMAL, null);

        assertThat(job.getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(job.isScheduled()).isFalse();
        // not "scheduled for now": it was never scheduled, and null is the only value that says so
        assertThat(job.getScheduledAt()).isNull();
    }

    @Test
    void aPastTimestampStartsTheJobQueuedBecauseItIsAlreadyDue() {
        Instant scheduledAt = Instant.now().minusSeconds(300);

        Job job = Job.create("sleep", "500", 3, Priority.NORMAL, scheduledAt);

        assertThat(job.getStatus()).isEqualTo(JobStatus.QUEUED);
        // kept even though it never went near the scheduled set: this is what was asked for
        assertThat(job.getScheduledAt()).isEqualTo(scheduledAt);
    }

    @Test
    void aTimestampEqualToNowIsDueRatherThanFuture() {
        Job job = Job.create("sleep", "500", 3, Priority.NORMAL, Instant.now());

        assertThat(job.getStatus()).isEqualTo(JobStatus.QUEUED);
    }

    @Test
    void theFourArgumentCreateStillMeansImmediate() {
        // every pre-v0.6 caller and every pre-v0.6 test is quietly asserting this
        Job job = Job.create("sleep", "100", 3, Priority.LOW);

        assertThat(job.getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(job.getScheduledAt()).isNull();
    }

    @Test
    void promotionMovesTheJobToQueuedWithoutTouchingItsAttemptBudgetOrItsSchedule() {
        Instant scheduledAt = Instant.now().plusSeconds(60);
        Job job = Job.create("sleep", "500", 3, Priority.HIGH, scheduledAt);
        Instant updatedBefore = job.getUpdatedAt();

        job.markQueuedFromSchedule();

        assertThat(job.getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(job.isScheduled()).isFalse();
        assertThat(job.getScheduledAt()).isEqualTo(scheduledAt);
        assertThat(job.getAttemptCount()).isZero();
        assertThat(job.getStartedAt()).isNull();
        assertThat(job.getUpdatedAt()).isAfterOrEqualTo(updatedBefore);
    }

    @Test
    void onlyAScheduledJobCanBeQueuedFromItsSchedule() {
        // the guard is what stops a duplicate delivery walking a job that has already run, or one
        // that is terminal, back into the scheduling path
        assertThatThrownBy(() -> Job.create("sleep", "1", 3).markQueuedFromSchedule())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("QUEUED");

        Job running = Job.create("sleep", "1", 3);
        running.markRunning();
        assertThatThrownBy(running::markQueuedFromSchedule)
                .isInstanceOf(IllegalStateException.class);

        Job succeeded = Job.create("sleep", "1", 3);
        succeeded.markRunning();
        succeeded.markSucceeded();
        assertThatThrownBy(succeeded::markQueuedFromSchedule)
                .isInstanceOf(IllegalStateException.class);

        Job failed = Job.create("always_fail", "", 3);
        failed.markRunning();
        failed.markFailed("boom");
        assertThatThrownBy(failed::markQueuedFromSchedule)
                .isInstanceOf(IllegalStateException.class);

        Job deadLettered = Job.create("always_fail", "", 1);
        deadLettered.markRunning();
        deadLettered.markDeadLettered("exhausted");
        assertThatThrownBy(deadLettered::markQueuedFromSchedule)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theRequestedTimeSurvivesEveryTransitionAndIsNeverConfusedWithRetryTiming() {
        Instant scheduledAt = Instant.now().plusSeconds(5);
        Instant retryDueAt = Instant.now().plusSeconds(90);
        Job job = Job.create("fail_n_times", "2", 3, Priority.HIGH, scheduledAt);

        job.markQueuedFromSchedule();
        job.markRunning();
        job.markRetrying("boom", retryDueAt);

        assertThat(job.getScheduledAt()).isEqualTo(scheduledAt);
        assertThat(job.getNextAttemptAt()).isEqualTo(retryDueAt);

        job.markRunning();
        assertThat(job.getScheduledAt()).isEqualTo(scheduledAt);
        // nextAttemptAt is working state and is cleared; scheduledAt is history and is not
        assertThat(job.getNextAttemptAt()).isNull();

        job.markDeadLettered("exhausted");
        assertThat(job.getScheduledAt()).isEqualTo(scheduledAt);

        job.prepareForReplay(3);
        assertThat(job.getScheduledAt()).isEqualTo(scheduledAt);

        job.markSucceeded();
        assertThat(job.getScheduledAt()).isEqualTo(scheduledAt);
    }

    @Test
    void replayDoesNotPutAJobBackIntoScheduled() {
        // a replay is a new operator action taken now, so re-honouring the original time - which
        // has almost always passed - would be either a no-op or a surprise. See NOTES.md
        Job job = Job.create("always_fail", "", 1, Priority.LOW, Instant.now().plusSeconds(2));
        job.markQueuedFromSchedule();
        job.markRunning();
        job.markDeadLettered("exhausted");

        job.prepareForReplay(3);

        assertThat(job.getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(job.isScheduled()).isFalse();
        assertThat(job.getScheduledAt()).isNotNull();
    }

    @Test
    void aRetryNeverReturnsAJobToScheduled() {
        Job job = Job.create("fail_n_times", "1", 3, Priority.NORMAL, Instant.now().plusSeconds(1));
        job.markQueuedFromSchedule();
        job.markRunning();

        job.markRetrying("boom", Instant.now().plusSeconds(2));

        assertThat(job.getStatus()).isEqualTo(JobStatus.RETRYING);
        assertThat(job.isScheduled()).isFalse();
    }
}
