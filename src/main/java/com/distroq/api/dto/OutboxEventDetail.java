package com.distroq.api.dto;

import com.distroq.effects.ResponseHash;
import com.distroq.model.OutboxEvent;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * The single-event view: everything in the summary, plus enough about the payload to diagnose a
 * problem and not enough to leak one.
 *
 * <p>{@code payloadSummary} is a size and a digest. Two events with the same digest carry the same
 * payload, which is the question an operator actually asks ("is this the same thing twice?"), and
 * a digest answers it without putting the bytes on the wire.
 */
public record OutboxEventDetail(OutboxEventSummary event,
                                int operatorRetryCount,
                                String lastOperatorReason,
                                PayloadSummary payloadSummary,
                                int terminalCeiling) {

    public record PayloadSummary(int sizeBytes, String sha256, boolean redacted) {
    }

    public static OutboxEventDetail from(OutboxEvent event, Instant now, int maxAttempts) {
        byte[] bytes = event.getPayload().getBytes(StandardCharsets.UTF_8);
        return new OutboxEventDetail(
                OutboxEventSummary.from(event, now),
                event.getOperatorRetryCount(),
                event.getLastOperatorReason(),
                new PayloadSummary(bytes.length, ResponseHash.of(event.getPayload()), true),
                event.terminalCeiling(maxAttempts));
    }
}
