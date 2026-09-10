package com.distroq.metrics;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps a user-supplied string usable as a metric label.
 *
 * <p>A job type is chosen by whoever submits the job, so it is unbounded by construction. Passing
 * it straight to a metric label is the classic way to turn a queue into a memory leak: every
 * distinct value creates a new time series that the registry keeps forever, and a caller that puts
 * an order number in the type field would create one per order.
 *
 * <p>So the first {@code limit} distinct types seen since startup keep their own series and
 * everything after that collapses into {@code other}. The cap is a hard ceiling on cardinality
 * rather than an eviction policy, because evicting would make a series reappear later with a gap
 * in it and make the totals wrong. Anything unparseable or over-long is {@code other} immediately.
 */
public final class BoundedTagValues {

    public static final String OVERFLOW = "other";
    public static final String UNKNOWN = "unknown";

    private static final int MAX_VALUE_LENGTH = 64;

    private final Set<String> admitted = ConcurrentHashMap.newKeySet();
    private final int limit;

    public BoundedTagValues(int limit) {
        this.limit = Math.max(1, limit);
    }

    public String valueFor(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }
        String candidate = raw.trim();
        if (candidate.length() > MAX_VALUE_LENGTH || !printable(candidate)) {
            return OVERFLOW;
        }
        if (admitted.contains(candidate)) {
            return candidate;
        }
        // racy at the boundary: two threads may both admit the (limit)th value, which costs one
        // extra series and is cheaper than serialising every metric call on a lock
        if (admitted.size() >= limit) {
            return OVERFLOW;
        }
        admitted.add(candidate);
        return candidate;
    }

    public int distinctAdmitted() {
        return admitted.size();
    }

    private static boolean printable(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == ':';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }
}
