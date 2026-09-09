package com.distroq.effects;

import java.util.Locale;
import java.util.UUID;

/**
 * How a protected effect is named.
 *
 * <p>Three different identities get confused with each other, so v0.8 keeps them apart by name:
 *
 * <ul>
 *   <li><b>Physical delivery attempt</b> — a Redis Stream entry ID plus the consumer holding it.
 *       New on every redelivery, including redelivery of work that already ran.</li>
 *   <li><b>Job attempt</b> — {@code job.attemptCount} and the {@code job_attempts} row. New on
 *       every execution, including one that reruns work a crashed worker had already done.</li>
 *   <li><b>Logical effect key</b> — this. Stable for the operation being protected, across both
 *       of the above.</li>
 * </ul>
 *
 * <p>The counter key deliberately excludes the attempt number. Keying on the attempt would make
 * every redelivery a new effect, which is precisely the duplicate the ledger exists to prevent.
 * A handler whose effects genuinely are per-attempt would use {@link #perAttempt} instead — the
 * choice belongs to the handler, because only the handler knows what "the same work" means.
 */
public final class EffectKeys {

    private static final int MAX_NAME_LENGTH = 200;

    private EffectKeys() {
    }

    /** {@code <job-id>:counter:<normalized-payload>} — one increment per job, however delivered. */
    public static String counter(UUID jobId, String normalizedName) {
        return jobId + ":counter:" + normalizedName;
    }

    /** {@code <job-id>:attempt:<attempt-number>} — for effects that really are per-attempt. */
    public static String perAttempt(UUID jobId, int attemptNumber) {
        return jobId + ":attempt:" + attemptNumber;
    }

    /**
     * Lower-cased, trimmed, internal whitespace collapsed, and truncated to fit the 255-character
     * key column. Normalisation is part of the identity: {@code "Orders:Daily"} and
     * {@code " orders:daily "} name the same counter, so they must not be able to increment it
     * twice.
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "default";
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "-");
        return normalized.length() <= MAX_NAME_LENGTH
                ? normalized
                : normalized.substring(0, MAX_NAME_LENGTH);
    }
}
