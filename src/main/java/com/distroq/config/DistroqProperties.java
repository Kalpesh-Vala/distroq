package com.distroq.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "distroq")
public record DistroqProperties(
        @DefaultValue("distroq:jobs:pending") String queueKey,
        @DefaultValue("distroq:jobs:delayed") String delayedKey,
        @DefaultValue Retry retry,
        @DefaultValue Dlq dlq,
        @DefaultValue PriorityTuning priority) {

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
}
