package com.distroq.dashboard.dto;

import java.time.Instant;

/**
 * What this build is and where it is running.
 *
 * <p>The absences are the design. There is no datasource URL, no Redis host, no connection string,
 * no filesystem path and no token: each of those either is a credential or contains one, and this
 * response is rendered into a browser that an operator will screenshot into a ticket.
 *
 * <p>{@code redisVersion} and {@code postgresVersion} are product versions read from
 * {@code INFO server} and JDBC metadata respectively. They name software, not locations.
 */
public record SystemView(String version,
                         String commit,
                         String commitAbbrev,
                         String branch,
                         String tags,
                         Instant buildTime,
                         Instant commitTime,
                         String environment,
                         String instanceId,
                         String javaVersion,
                         String javaVendorVersion,
                         String springBootVersion,
                         String redisVersion,
                         String postgresVersion,
                         String flywayVersion,
                         String flywayDescription,
                         Integer flywayPendingMigrations,
                         String liveness,
                         String readiness,
                         Instant startedAt,
                         long uptimeMs,
                         Instant lastSuccessfulHealthCheckAt,
                         long refreshIntervalMs,
                         boolean authenticationRequired) {
}
