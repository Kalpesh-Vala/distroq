package com.distroq.api;

import com.distroq.api.dto.OutboxEventDetail;
import com.distroq.api.dto.OutboxEventSummary;
import com.distroq.api.dto.OutboxRetryRequest;
import com.distroq.api.dto.ReconciliationResponse;
import com.distroq.api.dto.ReconciliationRunRequest;
import com.distroq.api.dto.ReliabilityActionResponse;
import com.distroq.config.DistroqProperties;
import com.distroq.model.OutboxEvent;
import com.distroq.model.OutboxStatus;
import com.distroq.model.ReliabilityActionType;
import com.distroq.outbox.OutboxCleanupService;
import com.distroq.outbox.OutboxEventType;
import com.distroq.outbox.OutboxOperatorService;
import com.distroq.reliability.ReconciliationService;
import com.distroq.repository.OutboxEventRepository;
import com.distroq.repository.ReliabilityActionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Local administrative endpoints for inspection and controlled repair.
 *
 * <p>Two rules hold across all of them. Nothing here changes normal job semantics — no endpoint
 * cancels, reschedules, reprioritises or completes a job, and the only writes are the ones an
 * operator explicitly asks for. And nothing here talks to Redis: repair means putting PostgreSQL
 * back into a state the relay can act on, and then letting the relay act on it, because a
 * controller that publishes directly is the dual write the outbox was built to remove.
 *
 * <p>{@code X-Admin-Reason} is required on every mutating call. See {@link AdminReason} for why
 * that is an audit device and not a security one.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    /** No authentication exists in v0.8, so the actor is a label rather than an identity. */
    private static final String DEFAULT_ACTOR = "operator";

    private static final int MAX_PAGE_SIZE = 200;

    private final OutboxEventRepository outboxEvents;
    private final ReliabilityActionRepository reliabilityActions;
    private final OutboxOperatorService operatorService;
    private final OutboxCleanupService cleanupService;
    private final ReconciliationService reconciliationService;
    private final AdminReason adminReason;
    private final int outboxMaxAttempts;

    public AdminController(OutboxEventRepository outboxEvents,
                           ReliabilityActionRepository reliabilityActions,
                           OutboxOperatorService operatorService,
                           OutboxCleanupService cleanupService,
                           ReconciliationService reconciliationService,
                           AdminReason adminReason,
                           DistroqProperties properties) {
        this.outboxEvents = outboxEvents;
        this.reliabilityActions = reliabilityActions;
        this.operatorService = operatorService;
        this.cleanupService = cleanupService;
        this.reconciliationService = reconciliationService;
        this.adminReason = adminReason;
        this.outboxMaxAttempts = properties.outbox().maxAttempts();
    }

    @GetMapping("/outbox")
    public Map<String, Object> listOutbox(
            @RequestParam(required = false) OutboxStatus status,
            @RequestParam(required = false) OutboxEventType eventType,
            @RequestParam(required = false) UUID aggregateId,
            @RequestParam(required = false) Instant createdAfter,
            @RequestParam(required = false) Instant createdBefore,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        Pageable pageable = PageRequest.of(Math.max(0, page),
                Math.clamp(size, 1, MAX_PAGE_SIZE), Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<OutboxEvent> found = outboxEvents.findAll(
                filter(status, eventType, aggregateId, createdAfter, createdBefore), pageable);

        Instant now = Instant.now();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("page", found.getNumber());
        response.put("size", found.getSize());
        response.put("totalElements", found.getTotalElements());
        response.put("totalPages", found.getTotalPages());
        response.put("events", found.getContent().stream()
                .map(event -> OutboxEventSummary.from(event, now))
                .toList());
        return response;
    }

    @GetMapping("/outbox/{eventId}")
    public ResponseEntity<OutboxEventDetail> getOutboxEvent(@PathVariable UUID eventId) {
        return outboxEvents.findById(eventId)
                .map(event -> OutboxEventDetail.from(event, Instant.now(), outboxMaxAttempts))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Re-arm a terminal event. 202 rather than 200: the event is back in the queue, and the
     * publication itself has not happened yet and will not happen on this thread.
     */
    @PostMapping("/outbox/{eventId}/retry")
    public ResponseEntity<OutboxEventDetail> retryOutboxEvent(
            @PathVariable UUID eventId,
            @RequestHeader(name = "X-Admin-Reason", required = false) String header,
            @RequestHeader(name = "X-Admin-Actor", required = false) String actor,
            @RequestBody(required = false) OutboxRetryRequest request) {

        String adminHeader = adminReason.requireHeader(header);
        String reason = adminReason.requireBody(request == null ? null : request.reason());
        OutboxEvent event = operatorService.retry(eventId, reason, actorOrDefault(actor), adminHeader);
        return ResponseEntity.accepted()
                .body(OutboxEventDetail.from(event, Instant.now(), outboxMaxAttempts));
    }

    /**
     * Retention cleanup on demand. Same rules as the scheduled sweep: PUBLISHED only, batch
     * limited, one audit row per deleted event.
     */
    @PostMapping("/outbox/cleanup")
    public Map<String, Object> cleanupOutbox(
            @RequestHeader(name = "X-Admin-Reason", required = false) String header,
            @RequestHeader(name = "X-Admin-Actor", required = false) String actor) {

        String reason = adminReason.requireHeader(header);
        OutboxCleanupService.CleanupResult result =
                cleanupService.cleanup(reason, actorOrDefault(actor));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("deleted", result.deleted());
        response.put("batchSize", result.batchSize());
        response.put("batchFull", result.batchFull());
        response.put("cutoff", result.cutoff());
        response.put("retentionWindowDays", cleanupService.retentionWindow().toDays());
        return response;
    }

    /** Read-only preview. No reason header, because nothing changes and nothing is audited. */
    @GetMapping("/reconciliation")
    public ReconciliationResponse reconciliation() {
        return ReconciliationResponse.from(reconciliationService.run(false,
                "Reconciliation preview", DEFAULT_ACTOR, true));
    }

    @PostMapping("/reconciliation/run")
    public ReconciliationResponse runReconciliation(
            @RequestHeader(name = "X-Admin-Reason", required = false) String header,
            @RequestHeader(name = "X-Admin-Actor", required = false) String actor,
            @RequestBody(required = false) ReconciliationRunRequest request) {

        String adminHeader = adminReason.requireHeader(header);
        String reason = adminReason.requireBody(request == null ? null : request.reason());
        boolean requested = request != null && request.autoRepairRequested();
        return ReconciliationResponse.from(reconciliationService.run(requested,
                reason + " | X-Admin-Reason: " + adminHeader, actorOrDefault(actor), true));
    }

    @GetMapping("/reliability-actions")
    public Map<String, Object> reliabilityActions(
            @RequestParam(required = false) ReliabilityActionType actionType,
            @RequestParam(required = false) UUID targetId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        Pageable pageable = PageRequest.of(Math.max(0, page), Math.clamp(size, 1, MAX_PAGE_SIZE));
        Page<com.distroq.model.ReliabilityAction> found;
        if (actionType != null && targetId != null) {
            found = reliabilityActions
                    .findByActionTypeAndTargetIdOrderByCreatedAtDesc(actionType, targetId, pageable);
        } else if (actionType != null) {
            found = reliabilityActions.findByActionTypeOrderByCreatedAtDesc(actionType, pageable);
        } else if (targetId != null) {
            found = reliabilityActions.findByTargetIdOrderByCreatedAtDesc(targetId, pageable);
        } else {
            found = reliabilityActions.findByOrderByCreatedAtDesc(pageable);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("page", found.getNumber());
        response.put("size", found.getSize());
        response.put("totalElements", found.getTotalElements());
        response.put("totalPages", found.getTotalPages());
        response.put("actions", found.getContent().stream()
                .map(ReliabilityActionResponse::from)
                .toList());
        return response;
    }

    private static Specification<OutboxEvent> filter(OutboxStatus status, OutboxEventType eventType,
                                                     UUID aggregateId, Instant createdAfter,
                                                     Instant createdBefore) {
        return (root, query, builder) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
            if (status != null) {
                predicates.add(builder.equal(root.get("status"), status));
            }
            if (eventType != null) {
                predicates.add(builder.equal(root.get("eventType"), eventType));
            }
            if (aggregateId != null) {
                predicates.add(builder.equal(root.get("aggregateId"), aggregateId));
            }
            if (createdAfter != null) {
                predicates.add(builder.greaterThanOrEqualTo(root.get("createdAt"), createdAfter));
            }
            if (createdBefore != null) {
                predicates.add(builder.lessThanOrEqualTo(root.get("createdAt"), createdBefore));
            }
            return builder.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
    }

    private static String actorOrDefault(String actor) {
        if (actor == null || actor.isBlank()) {
            return DEFAULT_ACTOR;
        }
        String trimmed = actor.trim();
        return trimmed.length() <= 255 ? trimmed : trimmed.substring(0, 255);
    }
}
