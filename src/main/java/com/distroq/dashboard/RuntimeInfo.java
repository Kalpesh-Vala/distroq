package com.distroq.dashboard;

import com.distroq.config.DistroqProperties;
import com.distroq.dashboard.dto.SystemView;
import com.distroq.health.SubsystemHealth;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.lang.management.ManagementFactory;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

/**
 * What this process is, in terms safe to render in a browser.
 *
 * <p>Everything omitted here was omitted deliberately. There is no {@code spring.datasource.url},
 * no Redis host, no pool configuration and no working directory: a JDBC URL contains a username
 * and frequently a password, a Redis URL contains a password, and a filesystem path tells an
 * attacker the deployment layout. What is left is software versions, a commit, a profile name and
 * an instance ID — enough to answer "which build is this and is it the one I deployed".
 *
 * <p>{@code redisVersion} and {@code postgresVersion} are cached after the first successful read.
 * Neither can change under a running process, and a failed read simply leaves the field null so
 * the UI shows "unavailable" rather than a stale value; the next call tries again.
 *
 * <p>Flyway is read once, at construction, for the same reason {@code FlywayHealthIndicator} does:
 * migrations run at startup and nothing afterwards applies one, so re-reading
 * {@code flyway_schema_history} on every poll is a query that can only give the same answer.
 */
@Component
public class RuntimeInfo {

    private static final Logger log = LoggerFactory.getLogger(RuntimeInfo.class);

    private final Environment environment;
    private final ApplicationAvailability availability;
    private final SubsystemHealth subsystemHealth;
    private final ObjectProvider<BuildProperties> buildProperties;
    private final ObjectProvider<GitProperties> gitProperties;
    private final StringRedisTemplate redis;
    private final DataSource dataSource;
    private final DashboardProperties dashboardProperties;
    private final DistroqProperties.Admin admin;

    private final String flywayVersion;
    private final String flywayDescription;
    private final Integer flywayPending;
    private final AtomicReference<String> redisVersion = new AtomicReference<>();
    private final AtomicReference<String> postgresVersion = new AtomicReference<>();

    public RuntimeInfo(Environment environment,
                       ApplicationAvailability availability,
                       SubsystemHealth subsystemHealth,
                       ObjectProvider<BuildProperties> buildProperties,
                       ObjectProvider<GitProperties> gitProperties,
                       ObjectProvider<Flyway> flyway,
                       StringRedisTemplate redis,
                       DataSource dataSource,
                       DashboardProperties dashboardProperties,
                       DistroqProperties distroqProperties) {
        this.environment = environment;
        this.availability = availability;
        this.subsystemHealth = subsystemHealth;
        this.buildProperties = buildProperties;
        this.gitProperties = gitProperties;
        this.redis = redis;
        this.dataSource = dataSource;
        this.dashboardProperties = dashboardProperties;
        this.admin = distroqProperties.admin();

        Flyway resolved = flyway.getIfAvailable();
        MigrationInfo current = null;
        Integer pending = null;
        if (resolved != null) {
            try {
                current = resolved.info().current();
                pending = resolved.info().pending().length;
            } catch (RuntimeException e) {
                // the message can carry the JDBC URL, which carries the username
                log.warn("Flyway migration state could not be read for the dashboard: {}",
                        e.getClass().getSimpleName());
            }
        }
        this.flywayVersion = current == null ? null : String.valueOf(current.getVersion());
        this.flywayDescription = current == null ? null : current.getDescription();
        this.flywayPending = pending;
    }

    public SystemView describe() {
        GitProperties git = gitProperties.getIfAvailable();
        BuildProperties build = buildProperties.getIfAvailable();
        long startedAtMillis = ManagementFactory.getRuntimeMXBean().getStartTime();

        return new SystemView(
                version(build),
                git == null ? null : git.getCommitId(),
                git == null ? null : git.getShortCommitId(),
                git == null ? null : git.getBranch(),
                git == null ? null : git.get("tags"),
                build == null ? null : build.getTime(),
                git == null ? null : git.getCommitTime(),
                profile(),
                environment.getProperty("distroq.instance.id", "unknown"),
                System.getProperty("java.version"),
                System.getProperty("java.vm.name") + " " + System.getProperty("java.vm.version"),
                SpringBootVersion.getVersion(),
                redisVersion(),
                postgresVersion(),
                flywayVersion,
                flywayDescription,
                flywayPending,
                availability.getLivenessState().name(),
                availability.getReadinessState().name(),
                Instant.ofEpochMilli(startedAtMillis),
                Math.max(0L, System.currentTimeMillis() - startedAtMillis),
                lastSuccessfulHealthCheck(),
                dashboardProperties.refreshIntervalMs(),
                admin.enabled() && admin.tokenConfigured());
    }

    private String version(BuildProperties build) {
        String filtered = environment.getProperty("distroq.service.version");
        if (filtered != null && !filtered.isBlank() && !filtered.startsWith("@")) {
            return filtered;
        }
        return build == null ? "unknown" : build.getVersion();
    }

    private String profile() {
        String[] active = environment.getActiveProfiles();
        String[] profiles = active.length > 0 ? active : environment.getDefaultProfiles();
        return String.join(",", Arrays.asList(profiles));
    }

    /** The most recent successful tick of any background subsystem. */
    private Instant lastSuccessfulHealthCheck() {
        Instant latest = null;
        for (SubsystemHealth.Subsystem subsystem : SubsystemHealth.Subsystem.values()) {
            Instant candidate = subsystemHealth.state(subsystem).lastSuccessAt();
            if (candidate != null && (latest == null || candidate.isAfter(latest))) {
                latest = candidate;
            }
        }
        return latest;
    }

    /** {@code INFO server}, for {@code redis_version} only. Nothing else from the reply is kept. */
    private String redisVersion() {
        String cached = redisVersion.get();
        if (cached != null) {
            return cached;
        }
        try {
            Properties info = redis.execute((org.springframework.data.redis.core.RedisCallback<
                    Properties>) connection -> connection.serverCommands().info("server"));
            String version = info == null ? null : info.getProperty("redis_version");
            if (version != null) {
                redisVersion.set(version);
            }
            return version;
        } catch (RuntimeException e) {
            log.debug("Redis version is unavailable: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    /** The product version from JDBC metadata. Never the URL it was read through. */
    private String postgresVersion() {
        String cached = postgresVersion.get();
        if (cached != null) {
            return cached;
        }
        try (Connection connection = dataSource.getConnection()) {
            String version = connection.getMetaData().getDatabaseProductVersion();
            if (version != null) {
                postgresVersion.set(version);
            }
            return version;
        } catch (SQLException | RuntimeException e) {
            log.debug("PostgreSQL version is unavailable: {}", e.getClass().getSimpleName());
            return null;
        }
    }
}
