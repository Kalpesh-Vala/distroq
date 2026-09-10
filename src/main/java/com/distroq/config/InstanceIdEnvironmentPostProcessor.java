package com.distroq.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.UUID;

/**
 * Gives every process a stable identity before logging is configured.
 *
 * <p>An {@code EnvironmentPostProcessor} rather than a bean because {@code distroq.instance.id} has
 * to exist by the time the logging system reads its configuration, which happens long before the
 * application context has any beans in it. A bean would have produced an instance ID that appeared
 * on the log lines after startup and not on the ones during it.
 *
 * <p>The hostname alone is not enough. Two instances on one host — which is exactly what the
 * multi-worker recovery test runs — would share it, and the whole point of the field is telling
 * their log lines apart, so a random suffix is always appended unless an operator has set
 * {@code DISTROQ_INSTANCE_ID} to something they control.
 */
public class InstanceIdEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final String PROPERTY = "distroq.instance.id";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment,
                                       SpringApplication application) {
        if (environment.containsProperty(PROPERTY)) {
            return;
        }
        environment.getPropertySources().addLast(new MapPropertySource(
                "distroqInstanceId", Map.of(PROPERTY, generate())));
    }

    static String generate() {
        return hostname() + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String hostname() {
        try {
            String host = InetAddress.getLocalHost().getHostName();
            return host == null || host.isBlank() ? "unknown-host" : host;
        } catch (UnknownHostException e) {
            return "unknown-host";
        }
    }
}
