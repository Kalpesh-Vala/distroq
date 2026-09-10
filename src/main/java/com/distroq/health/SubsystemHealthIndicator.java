package com.distroq.health;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;

import java.time.Duration;
import java.time.Instant;

/**
 * Health for one background loop.
 *
 * <p>The question is deliberately narrow: has this subsystem completed a tick recently enough that
 * work handed to this instance would actually be picked up? A subsystem that is disabled by
 * configuration is UP with {@code enabled=false}, because an operator who turned the relay off did
 * not thereby make the instance unfit to serve traffic; a subsystem that is enabled and has not
 * succeeded within its own budget is DOWN, because it has.
 *
 * <p>Only the exception's class name is published. The message may name a host, a database or a
 * payload, and this response is reachable by anything that can reach the actuator port.
 */
public class SubsystemHealthIndicator implements HealthIndicator {

    private final SubsystemHealth health;
    private final SubsystemHealth.Subsystem subsystem;
    private final Duration staleAfter;

    public SubsystemHealthIndicator(SubsystemHealth health, SubsystemHealth.Subsystem subsystem,
                                    Duration staleAfter) {
        this.health = health;
        this.subsystem = subsystem;
        this.staleAfter = staleAfter;
    }

    @Override
    public Health health() {
        SubsystemHealth.State state = health.state(subsystem);
        Instant now = Instant.now();
        Health.Builder builder = Health.status(Status.UP)
                .withDetail("subsystem", subsystem.label())
                .withDetail("lifecycle", state.lifecycle())
                .withDetail("enabled", state.lifecycle() != SubsystemHealth.Lifecycle.DISABLED)
                .withDetail("staleAfterMs", staleAfter.toMillis())
                .withDetail("consecutiveFailures", state.consecutiveFailures());
        if (state.lastErrorType() != null) {
            builder.withDetail("lastErrorType", state.lastErrorType());
        }
        if (state.lifecycle() == SubsystemHealth.Lifecycle.DISABLED) {
            return builder.build();
        }
        Duration since = state.sinceLastSuccess(now);
        if (since == null) {
            return builder.status(Status.DOWN).withDetail("reason", "no successful cycle yet")
                    .build();
        }
        builder.withDetail("sinceLastSuccessMs", since.toMillis());
        if (since.compareTo(staleAfter) > 0) {
            return builder.status(Status.DOWN)
                    .withDetail("reason", "no successful cycle within the configured budget")
                    .build();
        }
        return builder.build();
    }
}
