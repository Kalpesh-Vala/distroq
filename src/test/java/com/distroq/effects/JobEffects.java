package com.distroq.effects;

import com.distroq.model.EffectStatus;
import com.distroq.model.JobEffect;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.UUID;

/**
 * Builds ledger rows for tests.
 *
 * <p>Reflection, because production code has exactly one way to create a {@link JobEffect} — the
 * {@code INSERT ... ON CONFLICT} that is the claim — and adding a second one for the tests would
 * mean the tested path is no longer the only path.
 */
public final class JobEffects {

    private JobEffects() {
    }

    public static JobEffect started(String effectKey, UUID jobId, int attemptNumber,
                                    String effectType, Instant createdAt) {
        try {
            Constructor<JobEffect> constructor = JobEffect.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            JobEffect effect = constructor.newInstance();
            set(effect, "effectKey", effectKey);
            set(effect, "jobId", jobId);
            set(effect, "attemptNumber", attemptNumber);
            set(effect, "effectType", effectType);
            set(effect, "status", EffectStatus.STARTED);
            set(effect, "createdAt", createdAt);
            return effect;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not build a JobEffect for a test", e);
        }
    }

    private static void set(JobEffect effect, String name, Object value)
            throws ReflectiveOperationException {
        Field field = JobEffect.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(effect, value);
    }
}
