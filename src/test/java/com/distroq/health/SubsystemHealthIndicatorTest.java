package com.distroq.health;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Readiness for the background loops.
 *
 * <p>The interesting cases are the two that look like failures and are not: a subsystem an
 * operator switched off, and a subsystem whose queue is empty. Both must stay UP, because
 * readiness answers "should this instance be given work", and an idle instance is the best
 * possible candidate for it.
 */
class SubsystemHealthIndicatorTest {

    /** A negative budget, so any elapsed time is stale without the test having to sleep. */
    private static final Duration ALREADY_STALE = Duration.ofMillis(-1);

    private final SubsystemHealth health = new SubsystemHealth();

    @Test
    void aSubsystemThatHasNotStartedYetIsDown() {
        Health result = indicator(Duration.ofSeconds(30)).health();

        assertThat(result.getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void aSubsystemThatHasJustSucceededIsUp() {
        health.started(SubsystemHealth.Subsystem.OUTBOX_RELAY);
        health.succeeded(SubsystemHealth.Subsystem.OUTBOX_RELAY);

        assertThat(indicator(Duration.ofSeconds(30)).health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void aSubsystemWithNoSuccessInsideItsBudgetIsDown() {
        health.started(SubsystemHealth.Subsystem.OUTBOX_RELAY);
        health.succeeded(SubsystemHealth.Subsystem.OUTBOX_RELAY);

        // a negative budget makes any elapsed time stale, without the test having to sleep
        Health result = indicator(ALREADY_STALE).health();

        assertThat(result.getStatus()).isEqualTo(Status.DOWN);
        assertThat(result.getDetails()).containsEntry("reason",
                "no successful cycle within the configured budget");
    }

    @Test
    void aDisabledSubsystemIsUpBecauseSwitchingItOffIsNotAFault() {
        health.disabled(SubsystemHealth.Subsystem.OUTBOX_RELAY);

        Health result = indicator(ALREADY_STALE).health();

        assertThat(result.getStatus()).isEqualTo(Status.UP);
        assertThat(result.getDetails()).containsEntry("enabled", false);
    }

    @Test
    void aSuccessClearsTheConsecutiveFailureCountButKeepsTheLastErrorType() {
        health.started(SubsystemHealth.Subsystem.OUTBOX_RELAY);
        health.failed(SubsystemHealth.Subsystem.OUTBOX_RELAY, new IllegalStateException("boom"));
        health.failed(SubsystemHealth.Subsystem.OUTBOX_RELAY, new IllegalStateException("boom"));

        assertThat(health.state(SubsystemHealth.Subsystem.OUTBOX_RELAY).consecutiveFailures())
                .isEqualTo(2);

        health.succeeded(SubsystemHealth.Subsystem.OUTBOX_RELAY);

        SubsystemHealth.State state = health.state(SubsystemHealth.Subsystem.OUTBOX_RELAY);
        assertThat(state.consecutiveFailures()).isZero();
        assertThat(state.lastErrorType()).isEqualTo("IllegalStateException");
    }

    @Test
    void onlyTheExceptionClassNameIsPublished() {
        health.started(SubsystemHealth.Subsystem.OUTBOX_RELAY);
        health.failed(SubsystemHealth.Subsystem.OUTBOX_RELAY, new IllegalStateException(
                "Connection to jdbc:postgresql://db/distroq?password=hunter2 refused"));

        Health result = indicator(Duration.ofSeconds(30)).health();

        assertThat(result.getDetails().values().toString())
                .doesNotContain("hunter2")
                .doesNotContain("jdbc")
                .contains("IllegalStateException");
    }

    private SubsystemHealthIndicator indicator(Duration staleAfter) {
        return new SubsystemHealthIndicator(health, SubsystemHealth.Subsystem.OUTBOX_RELAY,
                staleAfter);
    }
}
