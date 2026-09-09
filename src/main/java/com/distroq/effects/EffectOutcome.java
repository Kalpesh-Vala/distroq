package com.distroq.effects;

import com.distroq.model.EffectStatus;

/** What happened to one protected effect on one execution. */
public record EffectOutcome(String effectKey, String effectType, EffectStatus status,
                            boolean newlyApplied, String responseHash, Long counterValue) {
}
