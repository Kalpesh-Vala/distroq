package com.distroq.queue;

import com.distroq.model.Priority;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The member format shared by both sorted sets: {@code <TIER>:<uuid>}.
 *
 * <p>A sorted set is one key with no tier of its own, so a promotion has to learn the destination
 * tier from the member itself. Encoding it there is what keeps range + {@code ZREM} + {@code XADD}
 * inside a single atomic script; a database lookup per promoted job would not fit.
 *
 * <p>The tier here is a routing hint, not the truth. PostgreSQL owns {@code jobs.priority}, and
 * when the two disagree the worker logs it and runs at the database tier — see {@code Worker}.
 *
 * <p>Introduced in v0.6 for the scheduled set and adopted by the v0.4 delayed set, which had been
 * building the same string by hand. One encoder, one parser, and the Lua on the other side of the
 * wire only has to know about the separator.
 */
public record SortedSetMember(UUID jobId, Priority priority) {

    /** Separates the tier from the UUID. Also hardcoded in the promotion script's {@code find}. */
    public static final char SEPARATOR = ':';

    public SortedSetMember {
        Objects.requireNonNull(jobId, "jobId");
        priority = Priority.orDefault(priority);
    }

    public static String encode(UUID jobId, Priority priority) {
        return new SortedSetMember(jobId, priority).encode();
    }

    public static String encode(UUID jobId, Priority priority, UUID outboxEventId) {
        Objects.requireNonNull(outboxEventId, "outboxEventId");
        return encode(jobId, priority) + SEPARATOR + outboxEventId;
    }

    public String encode() {
        return priority.name() + SEPARATOR + jobId;
    }

    /**
     * Empty for anything this application did not write: no separator, an unknown tier, or a
     * malformed UUID.
     *
     * <p>Deliberately stricter than the promotion script, which falls back to the default tier for
     * an unrecognised prefix so that a member written by an older or newer version still moves
     * rather than sticking. This parser is used for reporting, where guessing a tier would put a
     * job in a metrics bucket it does not belong to. Neither path throws: an unreadable member is
     * an operational curiosity, not a reason to fail a sweep or a metrics call.
     */
    public static Optional<SortedSetMember> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        int separator = raw.indexOf(SEPARATOR);
        if (separator < 0) {
            return Optional.empty();
        }
        String tier = raw.substring(0, separator);
        // Priority.parse resolves blank to NORMAL, which is right for an absent field and wrong
        // for a member that starts with the separator
        if (tier.isBlank()) {
            return Optional.empty();
        }
        Optional<Priority> priority = Priority.parse(tier);
        if (priority.isEmpty()) {
            return Optional.empty();
        }
        String remainder = raw.substring(separator + 1).trim();
        int eventSeparator = remainder.indexOf(SEPARATOR);
        String rawId = eventSeparator < 0 ? remainder : remainder.substring(0, eventSeparator);
        try {
            return Optional.of(new SortedSetMember(UUID.fromString(rawId), priority.get()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
