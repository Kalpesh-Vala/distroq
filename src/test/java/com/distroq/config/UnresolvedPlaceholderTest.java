package com.distroq.config;

import com.distroq.TestProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The unresolved-placeholder rule.
 *
 * <p>Spring's binder ignores a {@code ${VAR}} it cannot resolve and hands the literal text
 * through. That is convenient for optional values and dangerous for exactly one of them: a
 * production deployment that forgets to export {@code DISTROQ_ADMIN_TOKEN} would otherwise start
 * successfully with an administrative token whose value is printed in this repository.
 */
class UnresolvedPlaceholderTest {

    @Test
    void anUnresolvedPlaceholderIsNotAConfiguredValue() {
        assertThat(DistroqProperties.isConfigured("${DISTROQ_ADMIN_TOKEN}")).isFalse();
        assertThat(DistroqProperties.isConfigured("  ${DISTROQ_DB_PASSWORD}  ")).isFalse();
    }

    @Test
    void blankAndNullAreNotConfiguredEither() {
        assertThat(DistroqProperties.isConfigured(null)).isFalse();
        assertThat(DistroqProperties.isConfigured("")).isFalse();
        assertThat(DistroqProperties.isConfigured("   ")).isFalse();
    }

    @Test
    void aRealValueIsConfiguredEvenIfItContainsPunctuation() {
        assertThat(DistroqProperties.isConfigured("s3cret-$-value")).isTrue();
        assertThat(DistroqProperties.isConfigured("jdbc:postgresql://db:5432/distroq")).isTrue();
    }

    @Test
    void aTokenThatIsAnUnresolvedPlaceholderDoesNotAuthenticateAnyone() {
        DistroqProperties.Admin admin =
                new DistroqProperties.Admin(true, "${DISTROQ_ADMIN_TOKEN}", "admin", 500);

        assertThat(admin.tokenConfigured()).isFalse();
    }

    @Test
    void productionRefusesToStartOnAnUnresolvedAdminToken() {
        DistroqProperties properties = TestProperties.builder()
                .admin(new DistroqProperties.Admin(true, "${DISTROQ_ADMIN_TOKEN}", "admin", 500))
                .build();

        assertThat(ConfigurationValidator.validate(properties, true,
                "jdbc:postgresql://localhost:5433/distroq", "localhost", "validate", 6))
                .anyMatch(problem -> problem.startsWith("distroq.admin.token"));
    }

    @Test
    void productionRefusesToStartOnAnUnresolvedDatabaseUrl() {
        DistroqProperties properties = TestProperties.builder()
                .admin(new DistroqProperties.Admin(true, "s3cret", "admin", 500))
                .build();

        assertThat(ConfigurationValidator.validate(properties, true, "${DISTROQ_DB_URL}",
                "localhost", "validate", 6))
                .anyMatch(problem -> problem.contains("spring.datasource.url")
                        && problem.contains("unresolved placeholder"));
    }

    @Test
    void productionRefusesToStartOnAnUnresolvedRedisHost() {
        DistroqProperties properties = TestProperties.builder()
                .admin(new DistroqProperties.Admin(true, "s3cret", "admin", 500))
                .build();

        assertThat(ConfigurationValidator.validate(properties, true,
                "jdbc:postgresql://localhost:5433/distroq", "${DISTROQ_REDIS_HOST}", "validate", 6))
                .anyMatch(problem -> problem.contains("spring.data.redis.host")
                        && problem.contains("unresolved placeholder"));
    }
}
