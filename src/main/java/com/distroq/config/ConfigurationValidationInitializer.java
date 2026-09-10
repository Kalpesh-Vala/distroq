package com.distroq.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.List;

/**
 * Runs the configuration rules before the first bean is created.
 *
 * <p>An {@code ApplicationContextInitializer} rather than a {@code @PostConstruct} on a component,
 * because bean creation order is not something this can rely on. With a component, a bad Redis
 * setting would fail while building {@code stringRedisTemplate} — and the operator would be
 * handed a five-screen {@code UnsatisfiedDependencyException} whose root cause is three levels
 * down, instead of one line naming the property. The whole point of failing fast is that the
 * message is useful, and a message is only useful if nothing else has failed first.
 *
 * <p>The properties are bound here by hand, which duplicates what {@code @ConfigurationProperties}
 * will do a moment later. That is the price of running early, and it is cheap: the binder applies
 * the same {@code @DefaultValue} annotations and the same relaxed names, so the values validated
 * are the values the application will use.
 */
public class ConfigurationValidationInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    public static final String PRODUCTION_PROFILE = "production";

    private static final Logger log =
            LoggerFactory.getLogger(ConfigurationValidationInitializer.class);

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        ConfigurableEnvironment environment = context.getEnvironment();
        DistroqProperties properties = Binder.get(environment)
                .bind("distroq", Bindable.of(DistroqProperties.class))
                .orElseThrow(() -> new InvalidConfigurationException(
                        List.of("the distroq.* configuration block could not be bound; check "
                                + "application.yml for a malformed value")));

        boolean production = environment.matchesProfiles(PRODUCTION_PROFILE);
        List<String> problems = ConfigurationValidator.validate(properties, production,
                environment.getProperty("spring.datasource.url"),
                environment.getProperty("spring.data.redis.host"),
                environment.getProperty("spring.jpa.hibernate.ddl-auto", "validate"),
                environment.getProperty("spring.task.scheduling.pool-size", Integer.class, 1));

        if (!problems.isEmpty()) {
            throw new InvalidConfigurationException(problems);
        }
        String profiles = String.join(",", environment.getActiveProfiles());
        log.info("Configuration validated for profile(s) [{}]",
                profiles.isEmpty() ? String.join(",", environment.getDefaultProfiles()) : profiles);
    }
}
