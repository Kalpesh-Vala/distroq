package com.distroq.api.dto;

/**
 * {@code maxAttempts} is a nullable {@code Integer} so that an omitted field (fall back to the
 * configured default) is distinguishable from an explicit 0 (rejected).
 *
 * <p>{@code priority} is a {@code String} rather than the {@code Priority} enum on purpose. Bound
 * as the enum, Jackson would reject an unknown value during deserialization with its own message
 * about acceptable values for a type the caller has never heard of, and the controller would never
 * see the request. As a String the value reaches {@code Priority.parse} and a bad one produces a
 * 400 that names the tiers. Case-insensitive; null or blank means NORMAL.
 * <p>{@code scheduledAt} is a {@code String} for the same reason and a stronger one. Bound as an
 * {@code Instant}, Jackson accepts {@code 2026-09-07T15:30:00Z} and rejects
 * {@code 2026-09-07T17:30:00+02:00} — a valid ISO-8601 instant — with a deserialization error the
 * controller never sees. As a String the value reaches {@code ScheduledAtParser}, which accepts
 * both, normalises the offset away, and produces a 400 that names the field for anything else.
 * Absent or JSON null means immediate; blank is rejected.
 */
public record SubmitJobRequest(
        String type,
        String payload,
        Integer maxAttempts,
        String priority,
        String scheduledAt) {
}
