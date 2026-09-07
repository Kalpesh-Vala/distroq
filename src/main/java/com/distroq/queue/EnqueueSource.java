package com.distroq.queue;

import java.util.Arrays;
import java.util.Optional;

/**
 * Why a stream entry exists. Carried in the entry itself so that {@code XRANGE} on a live stream
 * is readable during an incident without joining back to PostgreSQL.
 */
public enum EnqueueSource {

    /** POST /api/jobs. */
    SUBMIT,

    /** Promoted out of the delayed sorted set by the retry sweep. */
    RETRY,

    /** POST /api/jobs/{id}/retry, replaying a dead-lettered job. */
    REPLAY,

    /** Moved off a v0.4 pending list by the one-time startup migration. */
    LEGACY_MIGRATION;

    /** Unrecognised or absent returns empty; the caller decides what an unlabelled entry means. */
    public static Optional<EnqueueSource> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String trimmed = raw.trim();
        return Arrays.stream(values())
                .filter(source -> source.name().equalsIgnoreCase(trimmed))
                .findFirst();
    }
}
