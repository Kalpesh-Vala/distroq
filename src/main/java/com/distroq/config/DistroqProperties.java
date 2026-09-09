package com.distroq.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "distroq")
public record DistroqProperties(
        /** v0.4 list base. From v0.5 it names only the keys the startup migration drains. */
        @DefaultValue("distroq:jobs:pending") String queueKey,
        @DefaultValue("distroq:jobs:delayed") String delayedKey,
        /** User-requested execution times. Deliberately not {@code delayedKey} — see NOTES.md. */
        @DefaultValue("distroq:jobs:scheduled") String scheduledKey,
        @DefaultValue("distroq:jobs:stream") String streamKey,
        @DefaultValue Retry retry,
        @DefaultValue Dlq dlq,
        @DefaultValue PriorityTuning priority,
        @DefaultValue Streams streams,
        @DefaultValue Scheduling scheduling,
        @DefaultValue Outbox outbox,
        @DefaultValue Worker worker,
        @DefaultValue Reconciliation reconciliation,
        @DefaultValue Effects effects,
        @DefaultValue Admin admin) {

    public record Retry(
            @DefaultValue("3") int defaultMaxAttempts,
            @DefaultValue("1000") long baseDelayMs,
            @DefaultValue("60000") long maxDelayMs,
            @DefaultValue("0.2") double jitterFactor,
            @DefaultValue("1000") long pollIntervalMs,
            @DefaultValue("100") int promoteBatchSize) {
    }

    /** {@code replayAttempts} is added to attemptCount on replay, not assigned to maxAttempts. */
    public record Dlq(
            @DefaultValue("3") int replayAttempts) {
    }

    /**
     * {@code starvationThreshold} is a count of dequeues, not a duration. It bounds how many
     * consecutive higher-tier jobs may be served before the lowest tier gets one — it does not
     * bound wall-clock waiting time. See NOTES.md.
     */
    public record PriorityTuning(
            @DefaultValue("10") int starvationThreshold) {
    }

    /**
     * Redis Streams delivery.
     *
     * <p>{@code groupName} is shared across all three priority streams; Redis keeps a separate
     * group and Pending Entries List per stream, so one name is not one queue.
     *
     * <p>{@code consumerNamePrefix} is only the prefix — a random suffix is appended per process.
     * Two instances sharing a consumer name would each believe the other's in-flight entries were
     * their own, which breaks reclaim.
     *
     * <p>{@code claimMinIdleMs} is the reclaim threshold, and it is also an upper bound on how
     * long a healthy worker may hold an entry before another worker treats it as abandoned. It
     * must exceed the longest expected job duration.
     */
    public record Streams(
            @DefaultValue("distroq-workers") String groupName,
            @DefaultValue("worker") String consumerNamePrefix,
            @DefaultValue("10000") long claimMinIdleMs,
            @DefaultValue("100") int claimBatchSize,
            @DefaultValue("1") int readCount,
            @DefaultValue("1000") long blockTimeoutMs,
            @DefaultValue("0") String groupStartId) {
    }

    /**
     * User-scheduled job promotion.
     *
     * <p>{@code pollIntervalMs} is the dominant term in scheduling latency: a job is promoted on
     * the first tick at or after its due time, so the mean delay it contributes is half the
     * interval and the worst case is the whole of it. Lowering it costs one {@code ZRANGEBYSCORE}
     * per tick against a set that is usually empty; it does not make the requested time a hard
     * guarantee, because stream delivery and worker availability are still in front of the job.
     *
     * <p>Mirrors {@link Retry} rather than reusing it. The two pollers sweep different sorted sets
     * for different reasons, and tying retry backoff resolution to user-scheduling resolution
     * would mean one could not be tuned without moving the other.
     */
    public record Scheduling(
            @DefaultValue("1000") long pollIntervalMs,
            @DefaultValue("100") int promoteBatchSize) {
    }

    /**
     * Relay, deduplication and retention.
     *
     * <p>{@code maxAttempts} is the relay budget for one operator generation of the event, not a
     * lifetime cap: the terminal ceiling is {@code maxAttempts * (operatorRetryCount + 1)}, so an
     * operator retry hands the event a fresh budget without erasing the attempts it already made.
     *
     * <p>{@code dedupeRetentionMs} is the TTL of the Redis marker that makes republication a
     * no-op. It is deliberately compared against {@code publishedRetentionDays} before a row is
     * deleted: deleting an outbox row while its marker is still alive is harmless, but deleting a
     * row whose marker has expired removes the only two pieces of evidence that the publication
     * ever happened. See NOTES.md.
     */
    public record Outbox(
            @DefaultValue("500") long pollIntervalMs,
            @DefaultValue("100") int batchSize,
            @DefaultValue("30000") long lockDurationMs,
            @DefaultValue("100") int maxAttempts,
            @DefaultValue("604800000") long dedupeRetentionMs,
            @DefaultValue("30") int publishedRetentionDays,
            @DefaultValue("90") int failedRetentionDays,
            @DefaultValue("3600000") long cleanupIntervalMs,
            @DefaultValue("500") int cleanupBatchSize,
            @DefaultValue("true") boolean relayEnabled,
            @DefaultValue("false") boolean failAfterPublish) {
    }

    public record Worker(
            @DefaultValue("1") int concurrency,
            @DefaultValue("30000") long executionLeaseMs,
            @DefaultValue("5000") long heartbeatIntervalMs) {
    }

    /**
     * Reconciliation between PostgreSQL intent and Redis publication state.
     *
     * <p>{@code autoRepair} is an upper bound, not a default. A request may ask for less than the
     * configuration allows and never for more, so turning repairs on is a deployment decision
     * rather than something an HTTP body can do.
     *
     * <p>{@code requeueFailedOutbox} is the one repair that is off even when {@code autoRepair}
     * is on: re-arming a terminal event is exactly the operator decision v0.8 refuses to make on
     * its own.
     */
    public record Reconciliation(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("30000") long pollIntervalMs,
            @DefaultValue("100") int batchSize,
            @DefaultValue("60000") long staleScheduledAfterMs,
            @DefaultValue("60000") long staleOutboxAfterMs,
            @DefaultValue("60000") long staleLeaseAfterMs,
            @DefaultValue("false") boolean autoRepair,
            @DefaultValue("false") boolean requeueFailedOutbox) {
    }

    /**
     * The side-effect ledger.
     *
     * <p>{@code staleStartedAfterMs} is how long a STARTED effect may sit before reconciliation
     * reports it. It is not a timeout after which the effect is assumed not to have happened —
     * that is unknowable — which is why {@code autoFailStale} defaults to false.
     */
    public record Effects(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("300000") long staleStartedAfterMs,
            @DefaultValue("false") boolean autoFailStale) {
    }

    /** v0.8 has no authentication. {@code X-Admin-Reason} is an audit trail, not authorization. */
    public record Admin(
            @DefaultValue("500") int maxReasonLength) {
    }
}
