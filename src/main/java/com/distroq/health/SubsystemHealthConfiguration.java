package com.distroq.health;

import com.distroq.config.DistroqProperties;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Names the three subsystem indicators and gives each one a budget derived from its own poll
 * interval.
 *
 * <p>Actuator takes the indicator's key from the bean name with {@code HealthIndicator} stripped,
 * so these method names are what appears under {@code /actuator/health} and what the readiness
 * group in {@code application.yml} refers to. Renaming a bean here silently drops it out of the
 * readiness group, which is why the two lists are documented together in README.md.
 *
 * <p>The multiplier is generous on purpose. A budget of one interval would flap on any single slow
 * tick, and a readiness probe that flaps takes a healthy instance out of rotation and puts it back
 * every few seconds. Ten intervals is long enough that only a genuinely wedged loop trips it.
 */
@Configuration(proxyBeanMethods = false)
public class SubsystemHealthConfiguration {

    private static final int STALE_MULTIPLIER = 10;
    private static final Duration MINIMUM_BUDGET = Duration.ofSeconds(30);

    @Bean
    public HealthIndicator outboxRelayHealthIndicator(SubsystemHealth health,
                                                      DistroqProperties properties) {
        return new SubsystemHealthIndicator(health, SubsystemHealth.Subsystem.OUTBOX_RELAY,
                budget(properties.outbox().pollIntervalMs()));
    }

    @Bean
    public HealthIndicator workerSubsystemHealthIndicator(SubsystemHealth health,
                                                          DistroqProperties properties) {
        // a worker loop's tick is one blocking read, so its budget follows the block timeout
        return new SubsystemHealthIndicator(health, SubsystemHealth.Subsystem.WORKER,
                budget(properties.streams().blockTimeoutMs()));
    }

    @Bean
    public HealthIndicator schedulerSubsystemHealthIndicator(SubsystemHealth health,
                                                             DistroqProperties properties) {
        // the slowest of the sweeps sharing the scheduler pool sets the budget for all of them
        long slowest = Math.max(properties.retry().pollIntervalMs(),
                properties.scheduling().pollIntervalMs());
        return new SubsystemHealthIndicator(health, SubsystemHealth.Subsystem.SCHEDULER,
                budget(slowest));
    }

    private static Duration budget(long pollIntervalMs) {
        Duration scaled = Duration.ofMillis(Math.max(1, pollIntervalMs) * STALE_MULTIPLIER);
        return scaled.compareTo(MINIMUM_BUDGET) < 0 ? MINIMUM_BUDGET : scaled;
    }
}
