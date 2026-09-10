package com.distroq.health;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which indicators each probe consults.
 *
 * <p>This is configuration rather than code, and it is the configuration most likely to be
 * "tidied" into something wrong: adding {@code db} and {@code redis} to liveness looks like an
 * improvement and turns a thirty-second database blip into a rolling restart of every instance,
 * because Kubernetes kills a container that fails liveness rather than merely taking it out of the
 * load-balancer pool.
 *
 * <p>Reading the shipped YAML rather than starting a context keeps the assertion honest: it fails
 * if someone edits the file, which is exactly when it should.
 */
class HealthGroupsTest {

    @Test
    void livenessAsksOnlyWhetherTheProcessIsAlive() {
        assertThat(group("liveness")).containsExactly("livenessState");
    }

    @Test
    void livenessDoesNotDependOnPostgresOrRedis() {
        assertThat(group("liveness"))
                .doesNotContain("db")
                .doesNotContain("redis")
                .doesNotContain("flyway");
    }

    @Test
    void readinessCoversEveryDependencyAnInstanceNeedsToMakeProgress() {
        assertThat(group("readiness")).containsExactlyInAnyOrder(
                "readinessState", "db", "redis", "flyway",
                "outboxRelay", "workerSubsystem", "schedulerSubsystem");
    }

    @Test
    void theReadinessGroupNamesTheIndicatorBeansThatActuallyExist() {
        // actuator derives an indicator's key from its bean name with "HealthIndicator" removed,
        // so a rename in SubsystemHealthConfiguration silently drops it out of the group
        assertThat(group("readiness"))
                .contains(beanKey("outboxRelayHealthIndicator"),
                        beanKey("workerSubsystemHealthIndicator"),
                        beanKey("schedulerSubsystemHealthIndicator"),
                        beanKey("flywayHealthIndicator"));
    }

    @Test
    void theActuatorSurfaceExcludesEndpointsThatWouldDiscloseConfiguration() {
        String exposed = String.valueOf(
                property("management.endpoints.web.exposure.include"));

        assertThat(exposed.split(","))
                .containsExactlyInAnyOrder("health", "info", "metrics", "prometheus");
        assertThat(exposed).doesNotContain("env").doesNotContain("configprops")
                .doesNotContain("beans").doesNotContain("heapdump").doesNotContain("threaddump");
    }

    @Test
    void theAdminTokenHasNoDefaultValueInTheShippedConfiguration() {
        assertThat(String.valueOf(property("distroq.admin.token"))).isEqualTo("${DISTROQ_ADMIN_TOKEN:}");
    }

    private static List<String> group(String name) {
        return List.of(String.valueOf(
                property("management.endpoint.health.group." + name + ".include")).split(","));
    }

    private static String beanKey(String beanName) {
        return beanName.replace("HealthIndicator", "");
    }

    private static Object property(String name) {
        try {
            List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                    .load("application.yml", new ClassPathResource("application.yml"));
            return sources.stream()
                    .map(source -> source.getProperty(name))
                    .filter(java.util.Objects::nonNull)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(name + " is not set in application.yml"));
        } catch (IOException e) {
            throw new AssertionError("application.yml could not be read", e);
        }
    }
}
