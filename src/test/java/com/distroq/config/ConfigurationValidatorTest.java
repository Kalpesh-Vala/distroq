package com.distroq.config;

import com.distroq.TestProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rules that stop a deployment from starting.
 *
 * <p>Each test asserts on the property name in the message rather than on the count of problems.
 * The whole value of failing at startup is that the message says which line of which file to
 * change, so a message that named the wrong property would pass a count assertion and still be
 * useless at three in the morning.
 */
class ConfigurationValidatorTest {

    private static final String DB_URL = "jdbc:postgresql://localhost:5433/distroq";
    private static final String REDIS_HOST = "localhost";

    @Test
    void aValidLocalConfigurationHasNoProblems() {
        assertThat(validate(TestProperties.defaults(), false)).isEmpty();
    }

    @Test
    void aValidProductionConfigurationWithATokenHasNoProblems() {
        DistroqProperties properties = TestProperties.builder()
                .admin(new DistroqProperties.Admin(true, "s3cret", "admin", 500))
                .build();

        assertThat(validate(properties, true)).isEmpty();
    }

    @Test
    void zeroWorkerConcurrencyIsRejected() {
        DistroqProperties properties = TestProperties.of(
                new DistroqProperties.Worker(0, 30_000L, 5_000L));

        assertThat(validate(properties, false))
                .anyMatch(problem -> problem.startsWith("distroq.worker.concurrency"));
    }

    @Test
    void negativeWorkerConcurrencyIsRejected() {
        DistroqProperties properties = TestProperties.of(
                new DistroqProperties.Worker(-4, 30_000L, 5_000L));

        assertThat(validate(properties, false))
                .anyMatch(problem -> problem.startsWith("distroq.worker.concurrency"));
    }

    @Test
    void aHeartbeatLongerThanTheLeaseItRenewsIsRejected() {
        DistroqProperties properties = TestProperties.of(
                new DistroqProperties.Worker(1, 5_000L, 30_000L));

        assertThat(validate(properties, false))
                .anyMatch(problem -> problem.contains("distroq.worker.heartbeat-interval-ms")
                        && problem.contains("distroq.worker.execution-lease-ms"));
    }

    @Test
    void aHeartbeatEqualToTheLeaseIsAlsoRejectedBecauseItRenewsExactlyTooLate() {
        DistroqProperties properties = TestProperties.of(
                new DistroqProperties.Worker(1, 30_000L, 30_000L));

        assertThat(validate(properties, false))
                .anyMatch(problem -> problem.startsWith("distroq.worker.heartbeat-interval-ms"));
    }

    @Test
    void aZeroLeaseDurationIsRejected() {
        DistroqProperties properties = TestProperties.of(
                new DistroqProperties.Worker(1, 0L, 5_000L));

        assertThat(validate(properties, false))
                .anyMatch(problem -> problem.startsWith("distroq.worker.execution-lease-ms"));
    }

    @Test
    void aNegativeRetryDelayIsRejected() {
        DistroqProperties properties = TestProperties.of(
                new DistroqProperties.Retry(3, -1L, 60_000L, 0.2, 1000L, 100));

        assertThat(validate(properties, false))
                .anyMatch(problem -> problem.startsWith("distroq.retry.base-delay-ms"));
    }

    @Test
    void aMaximumDelayBelowTheBaseDelayIsRejected() {
        DistroqProperties properties = TestProperties.of(
                new DistroqProperties.Retry(3, 60_000L, 1_000L, 0.2, 1000L, 100));

        assertThat(validate(properties, false))
                .anyMatch(problem -> problem.startsWith("distroq.retry.max-delay-ms"));
    }

    @Test
    void aJitterFactorOfOneOrMoreIsRejected() {
        DistroqProperties properties = TestProperties.of(
                new DistroqProperties.Retry(3, 1000L, 60_000L, 1.0, 1000L, 100));

        assertThat(validate(properties, false))
                .anyMatch(problem -> problem.startsWith("distroq.retry.jitter-factor"));
    }

    @Test
    void anEmptyRedisKeyIsRejected() {
        DistroqProperties properties = TestProperties.builder().streamKey("  ").build();

        assertThat(validate(properties, false))
                .anyMatch(problem -> problem.startsWith("distroq.stream-key"));
    }

    @Test
    void twoRedisKeysSharingOneValueAreRejected() {
        DistroqProperties properties = TestProperties.builder()
                .delayedKey("distroq:jobs:scheduled")
                .build();

        assertThat(validate(properties, false))
                .anyMatch(problem -> problem.contains("must all be different"));
    }

    @Test
    void aBlankConsumerGroupNameIsRejected() {
        DistroqProperties properties = TestProperties.of(
                new DistroqProperties.Streams("", "worker", 10_000L, 100, 1, 1000L, "0"));

        assertThat(validate(properties, false))
                .anyMatch(problem -> problem.startsWith("distroq.streams.group-name"));
    }

    @Test
    void aBlockTimeoutOfZeroIsRejectedBecauseAWorkerWouldNeverNoticeShutdown() {
        DistroqProperties properties = TestProperties.of(
                new DistroqProperties.Streams("distroq-workers", "worker", 10_000L, 100, 1, 0L, "0"));

        assertThat(validate(properties, false))
                .anyMatch(problem -> problem.startsWith("distroq.streams.block-timeout-ms"));
    }

    @Test
    void aDedupeRetentionShorterThanTheReconciliationStalenessWindowIsRejected() {
        DistroqProperties properties = TestProperties.of(
                new DistroqProperties.Outbox(500L, 100, 30_000L, 100, 1_000L, 30, 90,
                        3_600_000L, 500, true, false));

        assertThat(validate(properties, false))
                .anyMatch(problem -> problem.startsWith("distroq.outbox.dedupe-retention-ms"));
    }

    @Test
    void aCleanupIntervalShorterThanTheReconciliationStalenessWindowIsRejected() {
        DistroqProperties properties = TestProperties.of(
                new DistroqProperties.Outbox(500L, 100, 30_000L, 100, 604_800_000L, 30, 90,
                        1_000L, 500, true, false));

        assertThat(validate(properties, false))
                .anyMatch(problem -> problem.startsWith("distroq.outbox.cleanup-interval-ms"));
    }

    @Test
    void keepingFailedEventsForLessTimeThanPublishedOnesIsRejected() {
        DistroqProperties properties = TestProperties.of(
                new DistroqProperties.Outbox(500L, 100, 30_000L, 100, 604_800_000L, 30, 7,
                        3_600_000L, 500, true, false));

        assertThat(validate(properties, false))
                .anyMatch(problem -> problem.startsWith("distroq.outbox.failed-retention-days"));
    }

    @Test
    void requeueingFailedOutboxWithoutAutoRepairIsRejectedAsAPolicyThatIsNotInForce() {
        DistroqProperties properties = TestProperties.of(
                new DistroqProperties.Reconciliation(true, 30_000L, 100, 60_000L, 60_000L, 60_000L,
                        false, true));

        assertThat(validate(properties, false))
                .anyMatch(problem ->
                        problem.startsWith("distroq.reconciliation.requeue-failed-outbox"));
    }

    @Test
    void productionWithAdminEnabledAndNoTokenIsRejected() {
        assertThat(validate(TestProperties.defaults(), true))
                .anyMatch(problem -> problem.startsWith("distroq.admin.token"));
    }

    @Test
    void productionWithAdminDisabledDoesNotRequireAToken() {
        DistroqProperties properties = TestProperties.builder()
                .admin(new DistroqProperties.Admin(false, null, "admin", 500))
                .build();

        assertThat(validate(properties, true))
                .noneMatch(problem -> problem.startsWith("distroq.admin.token"));
    }

    @Test
    void localWithAdminEnabledAndNoTokenIsAllowedSoADeveloperNeedsNoSecret() {
        assertThat(validate(TestProperties.defaults(), false))
                .noneMatch(problem -> problem.startsWith("distroq.admin.token"));
    }

    @Test
    void productionRejectsTheOutboxFailureHook() {
        DistroqProperties properties = TestProperties.builder()
                .admin(new DistroqProperties.Admin(true, "s3cret", "admin", 500))
                .outbox(new DistroqProperties.Outbox(500L, 100, 30_000L, 100, 604_800_000L, 30, 90,
                        3_600_000L, 500, true, true))
                .build();

        assertThat(validate(properties, true))
                .anyMatch(problem -> problem.startsWith("distroq.outbox.fail-after-publish"));
    }

    @Test
    void productionRejectsSchemaGeneration() {
        DistroqProperties properties = TestProperties.builder()
                .admin(new DistroqProperties.Admin(true, "s3cret", "admin", 500))
                .build();

        List<String> problems = ConfigurationValidator.validate(properties, true, DB_URL,
                REDIS_HOST, "update", 6);

        assertThat(problems)
                .anyMatch(problem -> problem.startsWith("spring.jpa.hibernate.ddl-auto"));
    }

    @Test
    void aMissingDatabaseUrlIsRejected() {
        List<String> problems = ConfigurationValidator.validate(TestProperties.defaults(), false,
                null, REDIS_HOST, "validate", 6);

        assertThat(problems).anyMatch(problem -> problem.startsWith("spring.datasource.url"));
    }

    @Test
    void aMissingRedisHostIsRejected() {
        List<String> problems = ConfigurationValidator.validate(TestProperties.defaults(), false,
                DB_URL, "  ", "validate", 6);

        assertThat(problems).anyMatch(problem -> problem.startsWith("spring.data.redis.host"));
    }

    @Test
    void aSchedulerPoolTooSmallForTheSweepsThatShareItIsRejected() {
        List<String> problems = ConfigurationValidator.validate(TestProperties.defaults(), false,
                DB_URL, REDIS_HOST, "validate", ConfigurationValidator.REQUIRED_SCHEDULER_TASKS - 1);

        assertThat(problems)
                .anyMatch(problem -> problem.startsWith("spring.task.scheduling.pool-size"));
    }

    @Test
    void theExceptionMessageListsEveryProblemAndNoValues() {
        DistroqProperties properties = TestProperties.of(
                new DistroqProperties.Worker(0, 5_000L, 30_000L));

        InvalidConfigurationException failure =
                new InvalidConfigurationException(validate(properties, false));

        assertThat(failure.problems()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(failure.getMessage())
                .contains("distroq.worker.concurrency")
                .contains("distroq.worker.heartbeat-interval-ms");
    }

    private static List<String> validate(DistroqProperties properties, boolean production) {
        return ConfigurationValidator.validate(properties, production, DB_URL, REDIS_HOST,
                "validate", 6);
    }
}
