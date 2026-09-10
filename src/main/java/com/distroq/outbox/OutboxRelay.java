package com.distroq.outbox;

import com.distroq.config.DistroqProperties;
import com.distroq.health.SubsystemHealth;
import com.distroq.lifecycle.ShutdownState;
import com.distroq.metrics.DistroqMetrics;
import com.distroq.model.OutboxEvent;
import com.distroq.observability.Events;
import com.distroq.observability.LogContext;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRelayStore store;
    private final RedisOutboxPublisher publisher;
    private final ShutdownState shutdownState;
    private final SubsystemHealth subsystemHealth;
    private final DistroqMetrics metrics;
    private final boolean enabled;
    private final boolean failAfterPublish;

    public OutboxRelay(OutboxRelayStore store, RedisOutboxPublisher publisher,
                       ShutdownState shutdownState, SubsystemHealth subsystemHealth,
                       DistroqMetrics metrics, DistroqProperties properties) {
        this.store = store;
        this.publisher = publisher;
        this.shutdownState = shutdownState;
        this.subsystemHealth = subsystemHealth;
        this.metrics = metrics;
        this.enabled = properties.outbox().relayEnabled();
        this.failAfterPublish = properties.outbox().failAfterPublish();
    }

    @PostConstruct
    void register() {
        if (enabled) {
            subsystemHealth.started(SubsystemHealth.Subsystem.OUTBOX_RELAY);
        } else {
            subsystemHealth.disabled(SubsystemHealth.Subsystem.OUTBOX_RELAY);
        }
    }

    /**
     * One claim-publish-mark cycle.
     *
     * <p>A tick that claims nothing is still a successful tick. An empty outbox is the normal
     * state, and treating it as a failure would take a healthy instance out of the readiness
     * rotation the moment the backlog cleared.
     *
     * <p>No new claim is made once shutdown has begun. A claimed event is locked for
     * {@code lock-duration-ms}, so claiming one this process will not get around to publishing
     * delays it by the whole lock duration for nothing.
     */
    @Scheduled(fixedDelayString = "${distroq.outbox.poll-interval-ms:500}")
    public void relay() {
        if (!enabled || !shutdownState.isRunning()) {
            return;
        }
        try {
            for (UUID id : store.claimBatch()) {
                if (!shutdownState.isRunning()) {
                    // already claimed: the lock expires on its own and reconciliation reports it
                    return;
                }
                publishOne(id);
            }
            subsystemHealth.succeeded(SubsystemHealth.Subsystem.OUTBOX_RELAY);
        } catch (Exception e) {
            // a failure here is the claim itself, which means PostgreSQL is unreachable rather
            // than that any particular event is unpublishable
            if (!shutdownState.isRunning()) {
                log.debug("Outbox relay aborted during shutdown");
                return;
            }
            subsystemHealth.failed(SubsystemHealth.Subsystem.OUTBOX_RELAY, e);
            try (LogContext ignored = LogContext.event(Events.OUTBOX_FAILED).errorType(e)) {
                log.error("Outbox relay could not claim a batch; the next tick will retry", e);
            }
        }
    }

    private void publishOne(UUID id) {
        long startedAt = System.nanoTime();
        OutboxEvent event = null;
        try {
            event = store.get(id);
            String result = publisher.publish(event);
            if (failAfterPublish) {
                throw new IllegalStateException("Configured failure after Redis publication");
            }
            store.markPublished(id);
            Duration took = Duration.ofNanos(System.nanoTime() - startedAt);
            metrics.outboxPublished(event.getEventType(), took);
            try (LogContext ignored = LogContext.event(Events.OUTBOX_PUBLISHED)
                    .outboxEvent(id).eventType(event.getEventType())
                    .job(event.getAggregateId()).durationMs(took.toMillis())) {
                log.debug("Published outbox event as {}", result);
            }
        } catch (Exception failure) {
            OutboxRelayStore.FailureResult result = store.markFailed(id, failure);
            metrics.outboxPublishFailed(event == null ? null : event.getEventType(),
                    Duration.ofNanos(System.nanoTime() - startedAt), result.terminal());
            subsystemHealth.failed(SubsystemHealth.Subsystem.OUTBOX_RELAY, failure);
            try (LogContext ignored = LogContext.event(Events.OUTBOX_FAILED)
                    .outboxEvent(id)
                    .eventType(event == null ? null : event.getEventType())
                    .status(result.terminal() ? "FAILED" : "PENDING")
                    .errorType(failure)) {
                if (result.terminal()) {
                    log.error("Outbox event {} is now FAILED after {} attempts against a ceiling "
                            + "of {}; the relay will not claim it again until an operator retries "
                            + "it through POST /api/admin/outbox/{}/retry",
                            id, result.attemptCount(), result.ceiling(), id, failure);
                } else {
                    log.error("Outbox event {} failed on attempt {} of {}; it returns to PENDING",
                            id, result.attemptCount(), result.ceiling(), failure);
                }
            }
        }
    }
}