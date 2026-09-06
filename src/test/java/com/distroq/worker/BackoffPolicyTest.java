package com.distroq.worker;

import com.distroq.config.DistroqProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class BackoffPolicyTest {

    private static final long BASE_MS = 1000L;
    private static final long MAX_MS = 60_000L;
    private static final double JITTER = 0.2;

    private final BackoffPolicy policy = policyWith(JITTER);

    private static BackoffPolicy policyWith(double jitterFactor) {
        return new BackoffPolicy(new DistroqProperties(
                "distroq:jobs:pending",
                "distroq:jobs:delayed",
                new DistroqProperties.Retry(3, BASE_MS, MAX_MS, jitterFactor, 1000L, 100),
                new DistroqProperties.Dlq(3),
                new DistroqProperties.PriorityTuning(10)));
    }

    @Test
    void firstThreeAttemptsDoubleWithinTheJitterBand() {
        assertWithinJitterBand(policy.delayFor(1), 1000L);
        assertWithinJitterBand(policy.delayFor(2), 2000L);
        assertWithinJitterBand(policy.delayFor(3), 4000L);
    }

    @Test
    void withoutJitterTheProgressionIsExact() {
        BackoffPolicy exact = policyWith(0.0);

        assertThat(exact.delayFor(1)).isEqualTo(Duration.ofMillis(1000));
        assertThat(exact.delayFor(2)).isEqualTo(Duration.ofMillis(2000));
        assertThat(exact.delayFor(3)).isEqualTo(Duration.ofMillis(4000));
        assertThat(exact.delayFor(4)).isEqualTo(Duration.ofMillis(8000));
    }

    @Test
    void capIsRespectedOnceTheExponentialExceedsIt() {
        BackoffPolicy exact = policyWith(0.0);

        assertThat(exact.delayFor(7)).isEqualTo(Duration.ofMillis(MAX_MS));
        assertThat(exact.delayFor(20)).isEqualTo(Duration.ofMillis(MAX_MS));
        assertWithinJitterBand(policy.delayFor(12), MAX_MS);
    }

    @Test
    void doesNotOverflowAtHighAttemptCounts() {
        for (int attempt : new int[] {40, 63, 64, 1000, Integer.MAX_VALUE}) {
            Duration delay = policy.delayFor(attempt);
            assertThat(delay).isPositive();
            assertThat(delay.toMillis()).isLessThanOrEqualTo(Math.round(MAX_MS * (1 + JITTER)));
        }
    }

    @Test
    void neverReturnsZeroOrNegative() {
        BackoffPolicy tiny = new BackoffPolicy(new DistroqProperties(
                "q", "d", new DistroqProperties.Retry(3, 1L, 5L, 0.99, 1000L, 100),
                new DistroqProperties.Dlq(3), new DistroqProperties.PriorityTuning(10)));

        for (int attempt = 0; attempt < 50; attempt++) {
            assertThat(tiny.delayFor(attempt)).isPositive();
        }
    }

    @Test
    void jitterVariesAcrossCalls() {
        Set<Long> observed = new HashSet<>();
        IntStream.range(0, 50).forEach(i -> observed.add(policy.delayFor(5).toMillis()));

        assertThat(observed).hasSizeGreaterThan(1);
    }

    private void assertWithinJitterBand(Duration actual, long expectedMs) {
        assertThat(actual.toMillis())
                .isBetween(Math.round(expectedMs * (1 - JITTER)), Math.round(expectedMs * (1 + JITTER)));
    }
}
