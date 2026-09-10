package com.distroq.health;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * What each background subsystem last managed to do, and when.
 *
 * <p>Readiness needs to distinguish three states that look identical from outside the process: a
 * relay that is publishing, a relay that is running and failing every attempt, and a relay that
 * is not running at all. A liveness probe cannot tell them apart and neither can a connection
 * check, because the connection can be fine while the loop is wedged.
 *
 * <p>Each subsystem reports the outcome of its tick, and the indicators read the result. A tick
 * with nothing to do is a success: an idle queue is not a fault, and treating "no work" as a
 * failure would take a healthy instance out of rotation the moment the backlog cleared.
 *
 * <p>{@code lastErrorType} is a class name. Error messages can quote a connection string or a
 * payload, and this value is rendered into an HTTP health response.
 */
@Component
public class SubsystemHealth {

    public enum Subsystem {
        OUTBOX_RELAY("outboxRelay"),
        WORKER("worker"),
        SCHEDULER("scheduler");

        private final String label;

        Subsystem(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * Three states, not two. "Never started" and "switched off" both have no successful cycle to
     * report, and readiness must answer differently for them: the first is an instance that has
     * not proven it can poll yet, the second is an operator's decision.
     */
    public enum Lifecycle { NOT_STARTED, RUNNING, DISABLED }

    private final Map<Subsystem, AtomicReference<State>> states = new EnumMap<>(Subsystem.class);

    public SubsystemHealth() {
        for (Subsystem subsystem : Subsystem.values()) {
            states.put(subsystem, new AtomicReference<>(State.notStarted()));
        }
    }

    public void started(Subsystem subsystem) {
        states.get(subsystem).updateAndGet(state ->
                new State(Lifecycle.RUNNING, state.lastSuccessAt(), state.lastFailureAt(),
                        state.lastErrorType(), state.consecutiveFailures()));
    }

    public void succeeded(Subsystem subsystem) {
        states.get(subsystem).updateAndGet(state ->
                new State(Lifecycle.RUNNING, Instant.now(), state.lastFailureAt(),
                        state.lastErrorType(), 0));
    }

    public void failed(Subsystem subsystem, Throwable failure) {
        String errorType = failure == null ? "unknown" : failure.getClass().getSimpleName();
        states.get(subsystem).updateAndGet(state -> new State(state.lifecycle(),
                state.lastSuccessAt(), Instant.now(), errorType,
                state.consecutiveFailures() + 1));
    }

    /** Disabled by configuration: no cycles are expected, and that is not a fault. */
    public void disabled(Subsystem subsystem) {
        states.get(subsystem).set(new State(Lifecycle.DISABLED, null, null, null, 0));
    }

    public State state(Subsystem subsystem) {
        return states.get(subsystem).get();
    }

    public record State(Lifecycle lifecycle, Instant lastSuccessAt, Instant lastFailureAt,
                        String lastErrorType, long consecutiveFailures) {

        static State notStarted() {
            return new State(Lifecycle.NOT_STARTED, null, null, null, 0);
        }

        public Duration sinceLastSuccess(Instant now) {
            return lastSuccessAt == null ? null : Duration.between(lastSuccessAt, now);
        }
    }
}
