package com.distroq.health;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

/**
 * Whether the schema this binary expects is the schema the database has.
 *
 * <p>Flyway runs once at startup, so this looks like a startup concern rather than a health one.
 * It is not: a rolling deployment can point a new binary at a database an operator restored from a
 * backup taken before the last migration, and the failure then shows up as Hibernate validation
 * errors on the first request rather than as an unready instance. Reporting the applied version
 * makes that visible on the readiness probe instead.
 *
 * <p>The answer is computed once and cached. Migration state cannot change under a running
 * instance — nothing but startup applies migrations — so re-reading {@code flyway_schema_history}
 * on every scrape would be a query that can only ever give the same answer.
 */
@Component
public class FlywayHealthIndicator implements HealthIndicator {

    private final Health resolved;

    public FlywayHealthIndicator(Flyway flyway) {
        this.resolved = describe(flyway);
    }

    @Override
    public Health health() {
        return resolved;
    }

    private static Health describe(Flyway flyway) {
        try {
            MigrationInfo current = flyway.info().current();
            MigrationInfo[] pending = flyway.info().pending();
            Health.Builder builder = Health.status(Status.UP)
                    .withDetail("current", current == null ? "none"
                            : String.valueOf(current.getVersion()))
                    .withDetail("description", current == null ? "none" : current.getDescription())
                    .withDetail("pending", pending.length);
            return pending.length == 0 ? builder.build()
                    : builder.status(Status.DOWN)
                            .withDetail("reason", "migrations have not been applied")
                            .build();
        } catch (Exception e) {
            // the message can carry the JDBC URL, which carries the username
            return Health.status(Status.DOWN)
                    .withDetail("reason", "migration state could not be read")
                    .withDetail("errorType", e.getClass().getSimpleName())
                    .build();
        }
    }
}
