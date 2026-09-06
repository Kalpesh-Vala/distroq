package com.distroq.worker;

import com.distroq.config.DistroqProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Exponential backoff with jitter. Pure apart from the jitter draw, so it is unit-testable
 * without Redis or a database.
 */
@Component
public class BackoffPolicy {

    // 2^62 already dwarfs any sane max delay; beyond this the shift itself would overflow
    private static final int MAX_EXPONENT = 62;

    private final long baseDelayMs;
    private final long maxDelayMs;
    private final double jitterFactor;

    public BackoffPolicy(DistroqProperties properties) {
        this.baseDelayMs = properties.retry().baseDelayMs();
        this.maxDelayMs = properties.retry().maxDelayMs();
        this.jitterFactor = properties.retry().jitterFactor();
    }

    /**
     * @param attemptCount the number of attempts already made (1 after the first failure)
     */
    public Duration delayFor(int attemptCount) {
        long delay = cappedExponentialDelay(attemptCount);

        // Jitter is applied after the cap: without it, a burst of jobs that failed together
        // against one downed dependency would all retry at the same instant, hammering it in
        // synchronised waves - the thundering-herd problem.
        double multiplier = jitterFactor > 0
                ? 1.0 + ThreadLocalRandom.current().nextDouble(-jitterFactor, jitterFactor)
                : 1.0;

        return Duration.ofMillis(Math.max(1L, Math.round(delay * multiplier)));
    }

    private long cappedExponentialDelay(int attemptCount) {
        int exponent = Math.max(0, attemptCount - 1);
        if (exponent >= MAX_EXPONENT) {
            return maxDelayMs;
        }
        long factor = 1L << exponent;
        // clamp before multiplying rather than after, so the product can never overflow long
        long factorCeiling = maxDelayMs / Math.max(1L, baseDelayMs);
        if (factor > factorCeiling) {
            return maxDelayMs;
        }
        return Math.min(baseDelayMs * factor, maxDelayMs);
    }
}
