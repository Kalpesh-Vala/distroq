package com.distroq.dashboard.dto;

import java.time.Instant;
import java.util.List;

/**
 * The health of every dependency readiness depends on, as one row.
 *
 * <p>Each value is {@code UP}, {@code DOWN}, {@code OUT_OF_SERVICE} or {@code UNKNOWN}. Never a
 * boolean: "is Redis healthy: false" and "we could not find out" are different answers and the
 * dashboard must not collapse them.
 *
 * <p>{@code details} carries the indicator's own non-sensitive detail — the Flyway version, a
 * subsystem's lifecycle and consecutive failure count. The indicators already redact themselves
 * (see {@code FlywayHealthIndicator} and {@code SubsystemHealthIndicator}); nothing extra is
 * added here.
 */
public record HealthView(String liveness,
                         String readiness,
                         List<ComponentHealth> components,
                         Instant checkedAt) {

    public record ComponentHealth(String name, String status, String detail) {
    }
}
