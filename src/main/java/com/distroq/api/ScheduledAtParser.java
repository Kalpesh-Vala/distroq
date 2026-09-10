package com.distroq.api;

import com.distroq.api.error.ApiException;
import com.distroq.api.error.ErrorCode;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Turns the {@code scheduledAt} string on a submission into an {@link Instant}, or into a 400.
 *
 * <p>An {@code Instant} and nothing else. {@code LocalDateTime} would be a point on a calendar
 * with no idea which one, the JVM default zone is a property of whichever machine happened to
 * start the process, and the PostgreSQL server zone is a fourth answer again. All three make
 * "run this at 15:30" mean different things on different hosts, and none of them is detectable
 * from the response. Requiring an explicit offset moves that ambiguity to where it can be
 * rejected: the request.
 *
 * <p>{@code ISO_OFFSET_DATE_TIME} is the whole validation rule. It accepts {@code Z} and any
 * numeric offset, and it fails on a value that carries neither — which is why
 * {@code 2026-09-07T15:30:00} is a 400 rather than a silent reading in the server's zone. The
 * offset is applied and discarded: {@code 2026-09-07T17:30:00+02:00} and
 * {@code 2026-09-07T15:30:00Z} are the same instant and become the same stored value, the same
 * sorted-set score and the same API response.
 *
 * <p>Every rejection here happens before anything is written, so an invalid timestamp leaves no
 * PostgreSQL row and no Redis member behind.
 */
final class ScheduledAtParser {

    private ScheduledAtParser() {
    }

    /**
     * @param raw the request field: absent, JSON null, or a string
     * @return null for an immediate job, otherwise the requested instant
     * @throws ApiException {@code INVALID_SCHEDULED_AT}, naming the field, for anything unparseable
     */
    static Instant parse(String raw) {
        // absent and explicit null are the same request - "run it now" - and neither is an error
        if (raw == null) {
            return null;
        }
        // blank is not absent. Something sent the field and meant something by it, and the one
        // reading we can rule out is "immediately", which is what omitting it already says
        if (raw.isBlank()) {
            throw badRequest("scheduledAt must not be blank; omit the field entirely for "
                    + "immediate execution");
        }
        try {
            return OffsetDateTime.parse(raw.trim(), DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    .toInstant();
        } catch (DateTimeParseException e) {
            throw badRequest("scheduledAt must be an ISO-8601 timestamp with an explicit UTC "
                    + "offset, e.g. 2026-09-07T15:30:00Z or 2026-09-07T17:30:00+02:00, got '"
                    + raw + "'");
        }
    }

    private static ApiException badRequest(String reason) {
        return new ApiException(ErrorCode.INVALID_SCHEDULED_AT, reason);
    }
}
