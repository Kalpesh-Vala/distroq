package com.distroq.api.dto;

/**
 * {@code maxAttempts} is a nullable {@code Integer} so that an omitted field (fall back to the
 * configured default) is distinguishable from an explicit 0 (rejected).
 */
public record SubmitJobRequest(String type, String payload, Integer maxAttempts) {
}
