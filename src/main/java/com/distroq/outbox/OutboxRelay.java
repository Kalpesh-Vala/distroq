package com.distroq.outbox;

import com.distroq.config.DistroqProperties;
import com.distroq.model.OutboxEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRelayStore store;
    private final RedisOutboxPublisher publisher;
    private final boolean enabled;
    private final boolean failAfterPublish;

    public OutboxRelay(OutboxRelayStore store, RedisOutboxPublisher publisher,
                       DistroqProperties properties) {
        this.store = store;
        this.publisher = publisher;
        this.enabled = properties.outbox().relayEnabled();
        this.failAfterPublish = properties.outbox().failAfterPublish();
    }

    @Scheduled(fixedDelayString = "${distroq.outbox.poll-interval-ms:500}")
    public void relay() {
        if (!enabled) {
            return;
        }
        for (UUID id : store.claimBatch()) {
            try {
                OutboxEvent event = store.get(id);
                String result = publisher.publish(event);
                if (failAfterPublish) {
                    throw new IllegalStateException("Configured failure after Redis publication");
                }
                store.markPublished(id);
                log.debug("Published outbox event {} ({}) as {}", id, event.getEventType(), result);
            } catch (Exception failure) {
                OutboxRelayStore.FailureResult result = store.markFailed(id, failure);
                if (result.terminal()) {
                    log.error("Outbox event {} reached the terminal retry limit after {} attempts; "
                            + "it remains unpublished for operator inspection",
                            id, result.attemptCount(), failure);
                } else {
                    log.error("Outbox event {} failed on attempt {}; it remains unpublished",
                            id, result.attemptCount(), failure);
                }
            }
        }
    }
}