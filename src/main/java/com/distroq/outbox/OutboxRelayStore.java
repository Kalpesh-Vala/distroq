package com.distroq.outbox;

import com.distroq.config.DistroqProperties;
import com.distroq.model.OutboxEvent;
import com.distroq.repository.OutboxEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class OutboxRelayStore {

    private final OutboxEventRepository repository;
    private final DistroqProperties.Outbox properties;

    public OutboxRelayStore(OutboxEventRepository repository, DistroqProperties properties) {
        this.repository = repository;
        this.properties = properties.outbox();
    }

    @Transactional
    public List<UUID> claimBatch() {
        Instant now = Instant.now();
        Instant lockedUntil = now.plus(Duration.ofMillis(properties.lockDurationMs()));
        List<OutboxEvent> events = repository.claimable(now, properties.maxAttempts(),
                properties.batchSize());
        events.forEach(event -> event.claimUntil(lockedUntil));
        return events.stream().map(OutboxEvent::getId).toList();
    }

    @Transactional(readOnly = true)
    public OutboxEvent get(UUID id) {
        return repository.findById(id).orElseThrow();
    }

    @Transactional
    public void markPublished(UUID id) {
        repository.findById(id).ifPresent(event -> {
            if (event.getPublishedAt() == null) {
                event.markPublished(Instant.now());
            }
        });
    }

    @Transactional
    public FailureResult markFailed(UUID id, Throwable failure) {
        return repository.findById(id)
                .map(event -> {
                    event.markFailed(errorMessage(failure));
                    return new FailureResult(event.getAttemptCount(),
                            event.getAttemptCount() >= properties.maxAttempts());
                })
                .orElseGet(() -> new FailureResult(0, false));
    }

    private static String errorMessage(Throwable failure) {
        String message = failure.getMessage();
        String value = message == null || message.isBlank() ? failure.toString() : message;
        return value.length() <= 4000 ? value : value.substring(0, 4000);
    }

    public record FailureResult(int attemptCount, boolean terminal) {
    }
}