package com.distroq.model;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Discrete scheduling tiers, deliberately not an integer score.
 *
 * <p>A Redis list has no ordering primitive beyond insertion position, so an arbitrary integer
 * priority cannot be honoured on one: it would need either a sorted set scored by priority
 * (which gives up the blocking pop) or client-side scanning (which gives up atomicity). A small
 * fixed set of tiers maps onto one list per tier and keeps both. See NOTES.md for what this
 * cannot express.
 *
 * <p>v0.5 replaced the lists with Streams and the argument survived intact: a stream is also
 * append-ordered with no priority of its own, so the tiers are still one key each.
 *
 * <p>Declaration order is significant: it is the strict scheduling order, highest first.
 */
public enum Priority {
    HIGH,
    NORMAL,
    LOW;

    /** Absence of a priority is not an error, and it resolves here rather than at each call site. */
    public static final Priority DEFAULT = NORMAL;

    /** Strict scheduling order, highest first. */
    public static final List<Priority> STRICT_ORDER = List.of(values());

    /** The tier the starvation guard exists to protect. */
    public static final Priority LOWEST = STRICT_ORDER.get(STRICT_ORDER.size() - 1);

    public static Priority orDefault(Priority priority) {
        return priority == null ? DEFAULT : priority;
    }

    /**
     * Case-insensitive. Null or blank resolves to {@link #DEFAULT}; an unrecognised value returns
     * empty rather than throwing, so the caller owns the error message and its status code.
     */
    public static Optional<Priority> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.of(DEFAULT);
        }
        String trimmed = raw.trim();
        return Arrays.stream(values())
                .filter(priority -> priority.name().equalsIgnoreCase(trimmed))
                .findFirst();
    }

    /** For error messages, so a rejection names the options rather than just refusing. */
    public static String validValues() {
        return Arrays.stream(values()).map(Enum::name).collect(Collectors.joining(", "));
    }

    /**
     * Redis key suffix for this tier, e.g. {@code distroq:jobs:stream} + {@code :high}. Also the
     * suffix the one-time v0.4 list migration looks for on {@code distroq:jobs:pending}.
     */
    public String keySuffix() {
        return name().toLowerCase();
    }
}
