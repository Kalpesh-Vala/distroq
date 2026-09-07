package com.distroq.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v0.5 writes the attempt row before the attempt runs, so the transitions out of
 * {@link AttemptOutcome#IN_PROGRESS} are the part worth pinning down.
 */
class JobAttemptTest {

    private static final UUID JOB_ID = UUID.randomUUID();
    private static final Instant STARTED = Instant.parse("2026-09-07T10:00:00Z");
    private static final Instant FINISHED = Instant.parse("2026-09-07T10:00:05Z");

    @Test
    void anOpenAttemptRecordsWhoStartedItAndNothingElse() {
        JobAttempt attempt = JobAttempt.started(JOB_ID, "worker-aaaaaaaa", 1, STARTED);

        assertThat(attempt.getOutcome()).isEqualTo(AttemptOutcome.IN_PROGRESS);
        assertThat(attempt.isInProgress()).isTrue();
        assertThat(attempt.getWorkerId()).isEqualTo("worker-aaaaaaaa");
        assertThat(attempt.getAttemptNumber()).isEqualTo(1);
        assertThat(attempt.getStartedAt()).isEqualTo(STARTED);
        assertThat(attempt.getFinishedAt()).isNull();
        assertThat(attempt.getErrorMessage()).isNull();
    }

    @Test
    void inProgressToSuccess() {
        JobAttempt attempt = JobAttempt.started(JOB_ID, "worker-aaaaaaaa", 1, STARTED);

        attempt.succeed(FINISHED);

        assertThat(attempt.getOutcome()).isEqualTo(AttemptOutcome.SUCCESS);
        assertThat(attempt.getFinishedAt()).isEqualTo(FINISHED);
        assertThat(attempt.getErrorMessage()).isNull();
        assertThat(attempt.isInProgress()).isFalse();
    }

    @Test
    void inProgressToFailure() {
        JobAttempt attempt = JobAttempt.started(JOB_ID, "worker-aaaaaaaa", 2, STARTED);

        attempt.fail(FINISHED, "boom");

        assertThat(attempt.getOutcome()).isEqualTo(AttemptOutcome.FAILURE);
        assertThat(attempt.getFinishedAt()).isEqualTo(FINISHED);
        assertThat(attempt.getErrorMessage()).isEqualTo("boom");
        assertThat(attempt.getAttemptNumber()).isEqualTo(2);
    }

    @Test
    void inProgressToAbandonedNamesTheWorkerThatReclaimedIt() {
        JobAttempt attempt = JobAttempt.started(JOB_ID, "worker-aaaaaaaa", 1, STARTED);

        attempt.abandon(FINISHED, "Delivery 1-0 reclaimed by worker-bbbbbbbb");

        assertThat(attempt.getOutcome()).isEqualTo(AttemptOutcome.ABANDONED);
        assertThat(attempt.getFinishedAt()).isEqualTo(FINISHED);
        assertThat(attempt.getErrorMessage()).contains("worker-bbbbbbbb");
        assertThat(attempt.isInProgress()).isFalse();
    }

    @Test
    void abandoningDoesNotRewriteWhoRanItOrWhenItStarted() {
        // the row has to keep saying that worker-aaaaaaaa was executing, otherwise the history
        // loses the only record of who might have caused a side effect
        JobAttempt attempt = JobAttempt.started(JOB_ID, "worker-aaaaaaaa", 3, STARTED);

        attempt.abandon(FINISHED, "reclaimed");

        assertThat(attempt.getWorkerId()).isEqualTo("worker-aaaaaaaa");
        assertThat(attempt.getStartedAt()).isEqualTo(STARTED);
        assertThat(attempt.getAttemptNumber()).isEqualTo(3);
    }

    @Test
    void theTerminalFactoriesFromEarlierVersionsStillProduceClosedRows() {
        assertThat(JobAttempt.success(JOB_ID, "w", 1, STARTED, FINISHED).getOutcome())
                .isEqualTo(AttemptOutcome.SUCCESS);
        assertThat(JobAttempt.failure(JOB_ID, "w", 1, STARTED, FINISHED, "x").getOutcome())
                .isEqualTo(AttemptOutcome.FAILURE);
    }
}
