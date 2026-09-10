package com.distroq.lifecycle;

import com.distroq.observability.Events;
import com.distroq.observability.LogContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Step one of shutdown: stop being a candidate for new work, before anything else changes.
 *
 * <p>A {@code ContextClosedEvent} listener rather than a {@code SmartLifecycle}, because Spring
 * publishes that event at the very top of {@code AbstractApplicationContext.doClose()} — before
 * {@code lifecycleProcessor.onClose()} stops any {@code Lifecycle} bean, and therefore before Boot
 * begins draining the HTTP connector. A lifecycle bean, even at {@link Integer#MAX_VALUE}, runs
 * strictly later than this; there is nothing earlier to hook.
 *
 * <p>{@link Order} at highest precedence puts this ahead of the other listeners for the same
 * event, including Boot's own readiness transition, so the sequence in the log always starts here.
 *
 * <p>Nothing is waited for. Draining is {@link WorkDrainLifecycle}'s job, once HTTP has finished.
 */
@Component
public class ShutdownCoordinator {

    private static final Logger log = LoggerFactory.getLogger(ShutdownCoordinator.class);

    private final ApplicationContext context;
    private final ShutdownState shutdownState;

    public ShutdownCoordinator(ApplicationContext context, ShutdownState shutdownState) {
        this.context = context;
        this.shutdownState = shutdownState;
    }

    @EventListener(ContextClosedEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void onContextClosed() {
        if (!shutdownState.begin()) {
            return;
        }
        // readiness first, so a load balancer stops sending work while the requests already in
        // flight are still being served
        AvailabilityChangeEvent.publish(context, ReadinessState.REFUSING_TRAFFIC);
        try (LogContext ignored = LogContext.event(Events.APPLICATION_SHUTDOWN_STARTED)) {
            log.info("Shutdown started: readiness is DOWN and no new work will be claimed");
        }
    }
}
