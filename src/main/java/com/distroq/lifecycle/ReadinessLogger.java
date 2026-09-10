package com.distroq.lifecycle;

import com.distroq.observability.Events;
import com.distroq.observability.LogContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Records every readiness transition as a named event.
 *
 * <p>Readiness changes are the hardest thing to reconstruct after an incident: the probe result is
 * observed by the load balancer and by nothing that keeps history, so "when did this instance stop
 * taking traffic" is otherwise answerable only by correlating request volume. One log line per
 * transition makes it a query.
 */
@Component
public class ReadinessLogger {

    private static final Logger log = LoggerFactory.getLogger(ReadinessLogger.class);

    private final AtomicReference<ReadinessState> reported = new AtomicReference<>();

    @EventListener
    public void onReadinessChanged(AvailabilityChangeEvent<ReadinessState> event) {
        // Boot publishes REFUSING_TRAFFIC on context close as well, so the same transition arrives
        // twice during shutdown. Two identical lines would read as two transitions.
        if (reported.getAndSet(event.getState()) == event.getState()) {
            return;
        }
        try (LogContext ignored = LogContext.event(Events.APPLICATION_READINESS_CHANGED)
                .status(event.getState())) {
            log.info("Readiness is now {}", event.getState());
        }
    }
}
