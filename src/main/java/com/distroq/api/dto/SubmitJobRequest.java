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
 */
public record SubmitJobRequest(String type, String payload, Integer maxAttempts, String priority) {
}
