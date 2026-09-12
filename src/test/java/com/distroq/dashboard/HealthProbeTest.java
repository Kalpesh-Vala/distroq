package com.distroq.dashboard;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The health panel, and the one thing it must never do.
 *
 * <p>Spring's {@code AbstractHealthIndicator} records a failure as
 * {@code error: <class>: <message>}, and the message from a failed datasource check contains the
 * JDBC URL, which contains the username and sometimes the password. This response goes to a
 * browser. So details are allowlisted rather than filtered, and {@code error} is not on the list.
 */
class HealthProbeTest {

    private final ApplicationAvailability availability = availability();

    @Test
    void componentNamesFollowActuatorsOwnConvention() {
        assertThat(HealthProbe.componentName("dbHealthIndicator")).isEqualTo("db");
        assertThat(HealthProbe.componentName("redisHealthIndicator")).isEqualTo("redis");
        assertThat(HealthProbe.componentName("outboxRelayHealthIndicator"))
                .isEqualTo("outboxRelay");
        assertThat(HealthProbe.componentName("somethingElse")).isEqualTo("somethingElse");
    }

    @Test
    void aFailedIndicatorsRawErrorDetailNeverReachesTheResponse() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("error", "org.postgresql.util.PSQLException: FATAL: password authentication "
                + "failed for user \"distroq\" (jdbc:postgresql://db:5432/distroq?password=hunter2)");
        details.put("database", "PostgreSQL");

        String detail = HealthProbe.safeDetail(details);

        assertThat(detail).isEqualTo("database=PostgreSQL");
        assertThat(detail).doesNotContain("hunter2").doesNotContain("jdbc:")
                .doesNotContain("password");
    }

    @Test
    void structuralDetailsSurviveBecauseAnOperatorNeedsThem() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("subsystem", "outboxRelay");
        details.put("lifecycle", "RUNNING");
        details.put("consecutiveFailures", 3);
        details.put("errorType", "RedisConnectionFailureException");
        details.put("validationQuery", "isValid()");

        assertThat(HealthProbe.safeDetail(details))
                .contains("subsystem=outboxRelay")
                .contains("lifecycle=RUNNING")
                .contains("consecutiveFailures=3")
                .contains("errorType=RedisConnectionFailureException");
    }

    @Test
    void anIndicatorWithNothingSafeToSayReportsNoDetailRatherThanAnEmptyString() {
        assertThat(HealthProbe.safeDetail(Map.of("error", "boom"))).isNull();
        assertThat(HealthProbe.safeDetail(Map.of())).isNull();
        assertThat(HealthProbe.safeDetail(null)).isNull();
    }

    @Test
    void everyRegisteredIndicatorIsReportedAndTheListIsStable() {
        HealthProbe probe = new HealthProbe(Map.of(
                "redisHealthIndicator", () -> Health.up().withDetail("version", "7.2.4").build(),
                "dbHealthIndicator", () -> Health.down().withDetail("database", "PostgreSQL")
                        .withDetail("error", "jdbc:postgresql://db/distroq?password=hunter2")
                        .build()), availability);

        var view = probe.probe();

        assertThat(view.components()).extracting("name").containsExactly("db", "redis");
        assertThat(view.components()).extracting("status").containsExactly("DOWN", "UP");
        assertThat(view.components().toString()).doesNotContain("hunter2");
        assertThat(view.liveness()).isEqualTo("CORRECT");
        assertThat(view.readiness()).isEqualTo("ACCEPTING_TRAFFIC");
    }

    @Test
    void anIndicatorThatThrowsIsDownWithATypeAndNoMessage() {
        HealthIndicator exploding = () -> {
            throw new IllegalStateException("jdbc:postgresql://db/distroq?password=hunter2");
        };

        var view = new HealthProbe(Map.of("dbHealthIndicator", exploding), availability).probe();

        assertThat(view.components()).hasSize(1);
        assertThat(view.components().get(0).status()).isEqualTo(Status.DOWN.getCode());
        assertThat(view.components().get(0).detail()).isEqualTo("errorType=IllegalStateException");
    }

    private static ApplicationAvailability availability() {
        ApplicationAvailability mock = mock(ApplicationAvailability.class);
        when(mock.getLivenessState()).thenReturn(LivenessState.CORRECT);
        when(mock.getReadinessState()).thenReturn(ReadinessState.ACCEPTING_TRAFFIC);
        return mock;
    }
}
