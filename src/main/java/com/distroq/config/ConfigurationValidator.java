package com.distroq.config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The rules that decide whether a configuration is usable.
 *
 * <p>Most of these values have no safe fallback. A worker concurrency of zero starts a process
 * that consumes nothing and reports itself healthy; a heartbeat interval longer than the lease it
 * renews produces a worker that loses ownership of every job it runs, but only under load; an
 * outbox deduplication window shorter than the reconciliation staleness window produces duplicate
 * publications, but only after a Redis outage. Each of those is a support ticket weeks later. A
 * refusal to start is a stack trace now, with the property name in it.
 *
 * <p>The production checks are separate on purpose. A developer must be able to run this with no
 * secrets, a single worker and a throwaway password; a production deployment must not. The rule
 * for what belongs in the production-only list is whether the setting is merely inconvenient
 * locally or actually dangerous when exposed.
 *
 * <p>Pure functions over an already-bound {@link DistroqProperties}. Nothing here reads the
 * environment or touches Spring, so the rules can be tested without building a context — and so
 * {@link ConfigurationValidationInitializer} can run them before any bean exists.
 */
public final class ConfigurationValidator {

    /** The @Scheduled sweeps that must not block each other on the shared scheduler pool. */
    static final int REQUIRED_SCHEDULER_TASKS = 6;

    private ConfigurationValidator() {
    }

    public static List<String> validate(DistroqProperties properties, boolean production,
                                        String datasourceUrl, String redisHost, String ddlAuto,
                                        int schedulerPoolSize) {
        List<String> problems = new ArrayList<>();

        checkKeys(properties, problems);
        checkRetry(properties.retry(), problems);
        checkWorker(properties.worker(), problems);
        checkStreams(properties.streams(), problems);
        checkScheduling(properties.scheduling(), problems);
        checkOutbox(properties.outbox(), properties.reconciliation(), problems);
        checkReconciliation(properties.reconciliation(), problems);
        checkEffects(properties.effects(), problems);
        checkAdmin(properties.admin(), problems);
        checkShutdown(properties.shutdown(), problems);
        checkMetrics(properties.metrics(), problems);
        checkDlq(properties.dlq(), properties.priority(), problems);

        if (!DistroqProperties.isConfigured(datasourceUrl)) {
            problems.add("spring.datasource.url must be set to a resolved value; it is currently "
                    + (datasourceUrl == null || datasourceUrl.isBlank()
                            ? "empty" : "an unresolved placeholder"));
        }
        if (!DistroqProperties.isConfigured(redisHost)) {
            problems.add("spring.data.redis.host must be set to a resolved value; it is currently "
                    + (redisHost == null || redisHost.isBlank()
                            ? "empty" : "an unresolved placeholder"));
        }
        if (schedulerPoolSize < REQUIRED_SCHEDULER_TASKS) {
            problems.add("spring.task.scheduling.pool-size must be at least "
                    + REQUIRED_SCHEDULER_TASKS + " so the retry, scheduled-job, recovery, outbox "
                    + "relay, reconciliation and cleanup sweeps cannot block each other");
        }

        if (production) {
            checkProduction(properties, ddlAuto, problems);
        }
        return problems;
    }

    private static void checkProduction(DistroqProperties properties, String ddlAuto,
                                        List<String> problems) {
        if (properties.admin().enabled() && !properties.admin().tokenConfigured()) {
            problems.add("distroq.admin.token must be set when distroq.admin.enabled is true under "
                    + "the production profile; export DISTROQ_ADMIN_TOKEN. An unset environment "
                    + "variable binds as the literal placeholder text, which is why a blank value "
                    + "and an unresolved one are both refused here");
        }
        if (!"validate".equalsIgnoreCase(ddlAuto) && !"none".equalsIgnoreCase(ddlAuto)) {
            problems.add("spring.jpa.hibernate.ddl-auto must be 'validate' or 'none' under the "
                    + "production profile; Flyway is the only schema migration mechanism");
        }
        if (properties.outbox().failAfterPublish()) {
            problems.add("distroq.outbox.fail-after-publish is an acceptance-test hook that "
                    + "deliberately breaks publication and must be false under the production "
                    + "profile");
        }
        if (!properties.outbox().relayEnabled()) {
            problems.add("distroq.outbox.relay-enabled must be true under the production profile "
                    + "unless a separate relay deployment is running; with it false no job is ever "
                    + "enqueued");
        }
    }

    private static void checkKeys(DistroqProperties properties, List<String> problems) {
        requireKey("distroq.queue-key", properties.queueKey(), problems);
        requireKey("distroq.delayed-key", properties.delayedKey(), problems);
        requireKey("distroq.scheduled-key", properties.scheduledKey(), problems);
        requireKey("distroq.stream-key", properties.streamKey(), problems);

        Set<String> distinct = new LinkedHashSet<>(List.of(
                String.valueOf(properties.queueKey()), String.valueOf(properties.delayedKey()),
                String.valueOf(properties.scheduledKey()), String.valueOf(properties.streamKey())));
        if (distinct.size() != 4) {
            problems.add("distroq.queue-key, delayed-key, scheduled-key and stream-key must all be "
                    + "different; sharing one key would make retries, user schedules and stream "
                    + "entries overwrite each other");
        }
    }

    private static void checkRetry(DistroqProperties.Retry retry, List<String> problems) {
        if (retry.defaultMaxAttempts() < 1) {
            problems.add("distroq.retry.default-max-attempts must be at least 1");
        }
        if (retry.baseDelayMs() < 0) {
            problems.add("distroq.retry.base-delay-ms must not be negative");
        }
        if (retry.maxDelayMs() < retry.baseDelayMs()) {
            problems.add("distroq.retry.max-delay-ms must be greater than or equal to "
                    + "distroq.retry.base-delay-ms");
        }
        if (retry.jitterFactor() < 0 || retry.jitterFactor() >= 1) {
            problems.add("distroq.retry.jitter-factor must be at least 0 and less than 1");
        }
        if (retry.pollIntervalMs() < 1) {
            problems.add("distroq.retry.poll-interval-ms must be at least 1");
        }
        if (retry.promoteBatchSize() < 1) {
            problems.add("distroq.retry.promote-batch-size must be at least 1");
        }
    }

    private static void checkWorker(DistroqProperties.Worker worker, List<String> problems) {
        if (worker.concurrency() < 1) {
            problems.add("distroq.worker.concurrency must be at least 1; a value of "
                    + worker.concurrency() + " would start a process that consumes nothing");
        }
        if (worker.executionLeaseMs() < 1) {
            problems.add("distroq.worker.execution-lease-ms must be at least 1");
        }
        if (worker.heartbeatIntervalMs() < 1) {
            problems.add("distroq.worker.heartbeat-interval-ms must be at least 1");
        }
        if (worker.heartbeatIntervalMs() >= worker.executionLeaseMs()) {
            problems.add("distroq.worker.heartbeat-interval-ms must be shorter than "
                    + "distroq.worker.execution-lease-ms, otherwise the lease expires before it is "
                    + "ever renewed and every long job loses ownership of itself");
        }
    }

    private static void checkStreams(DistroqProperties.Streams streams, List<String> problems) {
        if (isBlank(streams.groupName())) {
            problems.add("distroq.streams.group-name must not be blank");
        }
        if (isBlank(streams.consumerNamePrefix())) {
            problems.add("distroq.streams.consumer-name-prefix must not be blank");
        }
        if (isBlank(streams.groupStartId())) {
            problems.add("distroq.streams.group-start-id must not be blank");
        }
        if (streams.claimMinIdleMs() < 1) {
            problems.add("distroq.streams.claim-min-idle-ms must be at least 1");
        }
        if (streams.claimBatchSize() < 1) {
            problems.add("distroq.streams.claim-batch-size must be at least 1");
        }
        if (streams.readCount() < 1) {
            problems.add("distroq.streams.read-count must be at least 1");
        }
        if (streams.blockTimeoutMs() < 1) {
            problems.add("distroq.streams.block-timeout-ms must be at least 1; a value of 0 blocks "
                    + "forever and a worker would never notice shutdown");
        }
    }

    private static void checkScheduling(DistroqProperties.Scheduling scheduling,
                                        List<String> problems) {
        if (scheduling.pollIntervalMs() < 1) {
            problems.add("distroq.scheduling.poll-interval-ms must be at least 1");
        }
        if (scheduling.promoteBatchSize() < 1) {
            problems.add("distroq.scheduling.promote-batch-size must be at least 1");
        }
    }

    private static void checkOutbox(DistroqProperties.Outbox outbox,
                                    DistroqProperties.Reconciliation reconciliation,
                                    List<String> problems) {
        if (outbox.pollIntervalMs() < 1) {
            problems.add("distroq.outbox.poll-interval-ms must be at least 1");
        }
        if (outbox.batchSize() < 1) {
            problems.add("distroq.outbox.batch-size must be at least 1");
        }
        if (outbox.lockDurationMs() < 1) {
            problems.add("distroq.outbox.lock-duration-ms must be at least 1");
        }
        if (outbox.maxAttempts() < 1) {
            problems.add("distroq.outbox.max-attempts must be at least 1");
        }
        if (outbox.dedupeRetentionMs() < 1) {
            problems.add("distroq.outbox.dedupe-retention-ms must be at least 1");
        }
        if (outbox.publishedRetentionDays() < 1) {
            problems.add("distroq.outbox.published-retention-days must be at least 1");
        }
        if (outbox.failedRetentionDays() < outbox.publishedRetentionDays()) {
            problems.add("distroq.outbox.failed-retention-days must be at least "
                    + "distroq.outbox.published-retention-days; a failed event is more useful to "
                    + "keep than a published one, not less");
        }
        if (outbox.cleanupIntervalMs() < 1) {
            problems.add("distroq.outbox.cleanup-interval-ms must be at least 1");
        }
        if (outbox.cleanupBatchSize() < 1) {
            problems.add("distroq.outbox.cleanup-batch-size must be at least 1");
        }

        long longestStaleWindow = longestStaleWindow(reconciliation);
        if (outbox.dedupeRetentionMs() < longestStaleWindow) {
            problems.add("distroq.outbox.dedupe-retention-ms must be at least as long as the "
                    + "longest distroq.reconciliation.stale-*-after-ms window (" + longestStaleWindow
                    + "ms); a deduplication marker that expires before reconciliation is willing to "
                    + "act on the event is how a repair becomes a duplicate publication");
        }
        if (outbox.cleanupIntervalMs() < longestStaleWindow) {
            problems.add("distroq.outbox.cleanup-interval-ms must be at least as long as the "
                    + "longest distroq.reconciliation.stale-*-after-ms window (" + longestStaleWindow
                    + "ms), otherwise retention can delete rows faster than reconciliation can "
                    + "observe them");
        }
    }

    private static void checkReconciliation(DistroqProperties.Reconciliation reconciliation,
                                            List<String> problems) {
        if (reconciliation.pollIntervalMs() < 1) {
            problems.add("distroq.reconciliation.poll-interval-ms must be at least 1");
        }
        if (reconciliation.batchSize() < 1) {
            problems.add("distroq.reconciliation.batch-size must be at least 1");
        }
        if (reconciliation.staleOutboxAfterMs() < 1) {
            problems.add("distroq.reconciliation.stale-outbox-after-ms must be at least 1");
        }
        if (reconciliation.staleScheduledAfterMs() < 1) {
            problems.add("distroq.reconciliation.stale-scheduled-after-ms must be at least 1");
        }
        if (reconciliation.staleLeaseAfterMs() < 1) {
            problems.add("distroq.reconciliation.stale-lease-after-ms must be at least 1");
        }
        if (reconciliation.requeueFailedOutbox() && !reconciliation.autoRepair()) {
            problems.add("distroq.reconciliation.requeue-failed-outbox has no effect unless "
                    + "distroq.reconciliation.auto-repair is also true; enabling only the narrower "
                    + "of the two reads as a repair policy that is not actually in force");
        }
    }

    private static void checkEffects(DistroqProperties.Effects effects, List<String> problems) {
        if (effects.staleStartedAfterMs() < 1) {
            problems.add("distroq.effects.stale-started-after-ms must be at least 1");
        }
    }

    private static void checkAdmin(DistroqProperties.Admin admin, List<String> problems) {
        if (admin.maxReasonLength() < 1) {
            problems.add("distroq.admin.max-reason-length must be at least 1");
        }
        if (isBlank(admin.defaultActor())) {
            problems.add("distroq.admin.default-actor must not be blank; every audit row needs an "
                    + "actor even when the caller declares none");
        }
    }

    private static void checkShutdown(DistroqProperties.Shutdown shutdown, List<String> problems) {
        if (shutdown.workerTimeoutMs() < 0) {
            problems.add("distroq.shutdown.worker-timeout-ms must not be negative");
        }
        if (shutdown.relayTimeoutMs() < 0) {
            problems.add("distroq.shutdown.relay-timeout-ms must not be negative");
        }
        if (shutdown.schedulerTimeoutMs() < 0) {
            problems.add("distroq.shutdown.scheduler-timeout-ms must not be negative");
        }
    }

    private static void checkMetrics(DistroqProperties.Metrics metrics, List<String> problems) {
        if (metrics.maxJobTypeTags() < 1) {
            problems.add("distroq.metrics.max-job-type-tags must be at least 1");
        }
        if (metrics.databaseGaugeCacheMs() < 0) {
            problems.add("distroq.metrics.database-gauge-cache-ms must not be negative");
        }
    }

    private static void checkDlq(DistroqProperties.Dlq dlq, DistroqProperties.PriorityTuning priority,
                                 List<String> problems) {
        if (dlq.replayAttempts() < 1) {
            problems.add("distroq.dlq.replay-attempts must be at least 1");
        }
        if (priority.starvationThreshold() < 1) {
            problems.add("distroq.priority.starvation-threshold must be at least 1");
        }
    }

    private static long longestStaleWindow(DistroqProperties.Reconciliation reconciliation) {
        return Math.max(reconciliation.staleOutboxAfterMs(),
                Math.max(reconciliation.staleScheduledAfterMs(),
                        reconciliation.staleLeaseAfterMs()));
    }

    private static void requireKey(String property, String value, List<String> problems) {
        if (isBlank(value)) {
            problems.add(property + " must not be blank");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
