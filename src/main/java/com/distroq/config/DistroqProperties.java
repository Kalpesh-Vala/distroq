package com.distroq.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "distroq")
public record DistroqProperties(
        /** v0.4 list base. From v0.5 it names only the keys the startup migration drains. */
        @DefaultValue("distroq:jobs:pending") String queueKey,
        @DefaultValue("distroq:jobs:delayed") String delayedKey,
        @DefaultValue("distroq:jobs:stream") String streamKey,
        @DefaultValue Retry retry,
        @DefaultValue Dlq dlq,
        @DefaultValue PriorityTuning priority,
        @DefaultValue Streams streams) {

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
}
