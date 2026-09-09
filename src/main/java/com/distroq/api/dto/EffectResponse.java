package com.distroq.api.dto;

import com.distroq.model.JobEffect;

import java.time.Instant;

/**
 * A protected side effect as it appears on a job.
 *
 * <p>{@code responseHash} rather than a response: the ledger never stores what the effect
 * returned, only enough to tell two observations of it apart. See NOTES.md.
 */
public record EffectResponse(String effectKey,
                             String effectType,
                             String status,
                             int attemptNumber,
                             String responseHash,
                             Instant createdAt,
                             Instant completedAt,
                             String errorMessage) {

    public static EffectResponse from(JobEffect effect) {
        return new EffectResponse(
                effect.getEffectKey(),
                effect.getEffectType(),
                effect.getStatus().name(),
                effect.getAttemptNumber(),
                effect.getResponseHash(),
                effect.getCreatedAt(),
                effect.getCompletedAt(),
                effect.getErrorMessage());
    }
}
