package com.distroq.dashboard;

import com.distroq.dashboard.dto.HealthView;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every registered health indicator, asked directly.
 *
 * <p>Deliberately not a call to {@code /actuator/health} from the browser. The actuator surface is
 * internal — README.md says so — and pointing a public page at it would either require exposing it
 * or require the dashboard to proxy it, and a proxy that forwards a health response forwards
 * whatever an indicator put in its details.
 *
 * <p>Which is the reason for {@link #SAFE_DETAIL_KEYS}. Spring's {@code AbstractHealthIndicator}
 * records a failure as {@code error: <exception class>: <message>}, and the message from a failed
 * datasource check contains the JDBC URL, which contains the username and sometimes the password.
 * So details are not passed through: only keys on this list survive, and {@code error} is not one
 * of them. A failure is reported as a status and an error <em>type</em>.
 *
 * <p>Asking an indicator is not free — {@code db} runs a validation query and {@code redis} runs a
 * {@code PING} — but both are the same cheap calls the readiness probe already makes on its own
 * schedule, and a health panel that reports a cached answer during an outage is the one thing it
 * must not do.
 */
@Component
public class HealthProbe {

    /**
     * Detail keys that are structural: enumerations, counts, durations and version numbers, all
     * written by an indicator in this repository or by Spring from non-sensitive metadata.
     */
    static final Set<String> SAFE_DETAIL_KEYS = Set.of(
            "database", "validationQuery", "version",
            "current", "pending", "description",
            "subsystem", "lifecycle", "enabled", "consecutiveFailures", "sinceLastSuccessMs",
            "staleAfterMs", "errorType", "reason",
            "total", "free", "threshold", "path", "exists");

    private static final String INDICATOR_SUFFIX = "HealthIndicator";

    private final Map<String, HealthIndicator> indicators;
    private final ApplicationAvailability availability;

    public HealthProbe(Map<String, HealthIndicator> indicators,
                       ApplicationAvailability availability) {
        this.indicators = indicators;
        this.availability = availability;
    }

    public HealthView probe() {
        List<HealthView.ComponentHealth> components = new ArrayList<>(indicators.size());
        indicators.forEach((beanName, indicator) ->
                components.add(describe(componentName(beanName), indicator)));
        components.sort(Comparator.comparing(HealthView.ComponentHealth::name));

        return new HealthView(availability.getLivenessState().name(),
                availability.getReadinessState().name(), List.copyOf(components), Instant.now());
    }

    /** Actuator's own convention: the bean name with {@code HealthIndicator} removed. */
    static String componentName(String beanName) {
        return beanName.endsWith(INDICATOR_SUFFIX)
                ? beanName.substring(0, beanName.length() - INDICATOR_SUFFIX.length())
                : beanName;
    }

    private static HealthView.ComponentHealth describe(String name, HealthIndicator indicator) {
        try {
            Health health = indicator.health();
            return new HealthView.ComponentHealth(name, health.getStatus().getCode(),
                    safeDetail(health.getDetails()));
        } catch (RuntimeException e) {
            // an indicator that throws rather than returning DOWN still must not leak its message
            return new HealthView.ComponentHealth(name, Status.DOWN.getCode(),
                    "errorType=" + e.getClass().getSimpleName());
        }
    }

    static String safeDetail(Map<String, Object> details) {
        if (details == null || details.isEmpty()) {
            return null;
        }
        Map<String, Object> kept = new LinkedHashMap<>();
        details.forEach((key, value) -> {
            if (SAFE_DETAIL_KEYS.contains(key) && value != null) {
                kept.put(key, value);
            }
        });
        if (kept.isEmpty()) {
            return null;
        }
        return kept.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + Redaction.error(String.valueOf(entry.getValue())))
                .reduce((left, right) -> left + ", " + right)
                .orElse(null);
    }
}
