package com.distroq.lifecycle;

import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One answer to "may I still take on new work?", shared by every producer and consumer.
 *
 * <p>Before v1.0 each loop kept its own flag and flipped it on {@code ContextClosedEvent}. Six
 * copies of the same rule is five too many, and it left no single place that could be consulted
 * before claiming a job. The flag is flipped by {@link ShutdownCoordinator}, which listens for the
 * same event at highest precedence.
 *
 * <p>The listener here is a fallback at lowest precedence, for a context assembled without the
 * coordinator — a slice test, say. It must never win the race, because {@link #begin} is what
 * decides who announces the shutdown, and announcing it from here would lose the readiness change.
 */
@Component
public class ShutdownState {

    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    /** True until shutdown begins. Every poll loop and scheduled sweep checks this first. */
    public boolean isRunning() {
        return !shuttingDown.get();
    }

    public boolean isShuttingDown() {
        return shuttingDown.get();
    }

    /** @return true for the caller that started the shutdown, so it is announced exactly once. */
    public boolean begin() {
        return shuttingDown.compareAndSet(false, true);
    }

    @EventListener(ContextClosedEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void onContextClosed() {
        shuttingDown.set(true);
    }
}
