package com.distroq.outbox;

import com.distroq.model.OutboxEvent;
import com.distroq.model.OutboxStatus;
import com.distroq.model.ReliabilityActionType;
import com.distroq.reliability.ReliabilityAuditService;
import com.distroq.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

/**
 * Operator repair of a terminal outbox event.
 *
 * <p>Three things this deliberately does not do. It does not create a second event, because a new
 * event ID would defeat the Redis deduplication marker that is the only thing standing between a
 * repaired event and a double publication. It does not publish to Redis, because a controller
 * that writes to Redis is exactly the dual write the outbox exists to remove. And it does not
 * reset {@code attemptCount}, because the history of a event that has failed two hundred times is
 * the most useful thing an operator has.
 */
@Service
public class OutboxOperatorService {

    private static final Logger log = LoggerFactory.getLogger(OutboxOperatorService.class);

    private final OutboxEventRepository repository;
    private final ReliabilityAuditService audit;

    public OutboxOperatorService(OutboxEventRepository repository, ReliabilityAuditService audit) {
        this.repository = repository;
        this.audit = audit;
    }

    @Transactional
    public OutboxEvent retry(UUID eventId, String reason, String actor, String adminReason) {
        OutboxEvent event = repository.findById(eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No outbox event with id " + eventId));
        if (event.getStatus() != OutboxStatus.FAILED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only a FAILED outbox event can be retried; event " + eventId + " is "
                            + event.getStatus());
        }

        String before = describe(event);
        event.operatorRetry(reason, Instant.now());
        audit.record(ReliabilityActionType.OUTBOX_RETRY, "OutboxEvent", eventId,
                reason + " | X-Admin-Reason: " + adminReason, actor, before, describe(event));

        log.warn("Operator {} returned outbox event {} to PENDING (operator retry {}): {}",
                actor, eventId, event.getOperatorRetryCount(), reason);
        return event;
    }

    /** Structural only. No payload, no {@code lastError}, nothing that could carry user data. */
    static String describe(OutboxEvent event) {
        return "status=" + event.getStatus()
                + " attemptCount=" + event.getAttemptCount()
                + " operatorRetryCount=" + event.getOperatorRetryCount()
                + " publishedAt=" + event.getPublishedAt()
                + " terminalFailedAt=" + event.getTerminalFailedAt();
    }
}
