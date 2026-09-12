package com.distroq.dashboard;

import com.distroq.observability.Events;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The readiness transitions this process has seen, so the activity feed can show them.
 *
 * <p>Every other event on the dashboard's feed is reconstructed from a database row. Readiness is
 * not written down anywhere — {@link com.distroq.lifecycle.ReadinessLogger} emits a log line and
 * nothing more — and the dashboard is explicitly forbidden from reading logs. A small in-memory
 * ring is the honest alternative: it holds no state anything depends on, it is not persisted to
 * PostgreSQL or Redis, and it is empty after a restart, which the UI labels rather than hides.
 *
 * <p>Consequences worth knowing: the feed shows this instance's transitions only, and only since
 * it started. Both are stated in README.md rather than papered over.
 */
@Component
public class ReadinessJournal {

    static final int CAPACITY = 20;

    public record Transition(String event, Instant at, String state) {
    }

    private final Deque<Transition> transitions = new ArrayDeque<>(CAPACITY);
    private final AtomicReference<ReadinessState> reported = new AtomicReference<>();

    @EventListener
    public void onReadinessChanged(AvailabilityChangeEvent<ReadinessState> event) {
        // Boot republishes REFUSING_TRAFFIC on context close; two rows would read as two changes
        if (reported.getAndSet(event.getState()) == event.getState()) {
            return;
        }
        record(new Transition(Events.APPLICATION_READINESS_CHANGED, Instant.now(),
                event.getState().name()));
    }

    private synchronized void record(Transition transition) {
        while (transitions.size() >= CAPACITY) {
            transitions.removeLast();
        }
        transitions.addFirst(transition);
    }

    /** Newest first. */
    public synchronized List<Transition> recent() {
        return List.copyOf(transitions);
    }
}
