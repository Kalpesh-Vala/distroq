package com.distroq.api.dto;

/** Body of {@code POST /api/admin/outbox/{eventId}/retry}. */
public record OutboxRetryRequest(String reason) {
}
