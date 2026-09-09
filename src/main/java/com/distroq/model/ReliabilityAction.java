package com.distroq.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One row per mutation performed by an operator or by reconciliation.
 *
 * <p>Written in the same transaction as the state change it describes, so there is no window in
 * which the database has been repaired and nothing says who repaired it. Payloads and errors from
 * user data are never copied here: {@code beforeState} and {@code afterState} hold short
 * structural summaries, not evidence that has to be redacted later.
 */
@Entity
@Table(name = "reliability_actions")
public class ReliabilityAction {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 100)
    private ReliabilityActionType actionType;

    @Column(nullable = false, length = 100)
    private String targetType;

    private UUID targetId;

    @Column(nullable = false, columnDefinition = "text")
    private String reason;

    @Column(nullable = false)
    private String actor;

    @Column(columnDefinition = "text")
    private String beforeState;

    @Column(columnDefinition = "text")
    private String afterState;

    @Column(nullable = false)
    private Instant createdAt;

    protected ReliabilityAction() {
        // for JPA
    }

    public static ReliabilityAction of(ReliabilityActionType actionType, String targetType,
                                       UUID targetId, String reason, String actor,
                                       String beforeState, String afterState) {
        ReliabilityAction action = new ReliabilityAction();
        action.id = UUID.randomUUID();
        action.actionType = actionType;
        action.targetType = targetType;
        action.targetId = targetId;
        action.reason = reason;
        action.actor = actor;
        action.beforeState = beforeState;
        action.afterState = afterState;
        action.createdAt = Instant.now();
        return action;
    }

    public UUID getId() { return id; }
    public ReliabilityActionType getActionType() { return actionType; }
    public String getTargetType() { return targetType; }
    public UUID getTargetId() { return targetId; }
    public String getReason() { return reason; }
    public String getActor() { return actor; }
    public String getBeforeState() { return beforeState; }
    public String getAfterState() { return afterState; }
    public Instant getCreatedAt() { return createdAt; }
}
