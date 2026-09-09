package com.distroq.api.dto;

import com.distroq.model.ReliabilityAction;

import java.time.Instant;
import java.util.UUID;

public record ReliabilityActionResponse(UUID id,
                                        String actionType,
                                        String targetType,
                                        UUID targetId,
                                        String reason,
                                        String actor,
                                        String beforeState,
                                        String afterState,
                                        Instant createdAt) {

    public static ReliabilityActionResponse from(ReliabilityAction action) {
        return new ReliabilityActionResponse(
                action.getId(),
                action.getActionType().name(),
                action.getTargetType(),
                action.getTargetId(),
                action.getReason(),
                action.getActor(),
                action.getBeforeState(),
                action.getAfterState(),
                action.getCreatedAt());
    }
}
