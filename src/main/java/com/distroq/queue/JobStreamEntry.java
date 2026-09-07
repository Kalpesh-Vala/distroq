package com.distroq.queue;

import com.distroq.model.Priority;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The body of a stream entry.
 *
 * <p>Deliberately not the job. PostgreSQL is the source of truth for payload, status and attempt
 * budget; Redis carries the identifier and just enough context to read a stream by eye.
 *
 * <p>{@code priority} is duplicated from the database on purpose: the promotion script needs it to
 * pick a destination stream without a per-job lookup, and it lets the worker notice a routing
 * disagreement instead of silently executing at the wrong tier. When the two disagree the database
 * wins — see {@code Worker}.
 */
public record JobStreamEntry(UUID jobId, Priority priority, long enqueuedAt, EnqueueSource source) {

    public static final String FIELD_JOB_ID = "jobId";
    public static final String FIELD_PRIORITY = "priority";
    public static final String FIELD_ENQUEUED_AT = "enqueuedAt";
    public static final String FIELD_SOURCE = "source";

    public JobStreamEntry {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(source, "source");
        priority = Priority.orDefault(priority);
    }

    public static JobStreamEntry now(UUID jobId, Priority priority, EnqueueSource source) {
        return new JobStreamEntry(jobId, priority, System.currentTimeMillis(), source);
    }

    public Map<String, String> toFields() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(FIELD_JOB_ID, jobId.toString());
        fields.put(FIELD_PRIORITY, priority.name());
        fields.put(FIELD_ENQUEUED_AT, Long.toString(enqueuedAt));
        fields.put(FIELD_SOURCE, source.name());
        return fields;
    }

    /**
     * Empty when the entry carries no usable job ID, which is the only field that cannot be
     * defaulted. An entry that fails to parse can still be acknowledged — it just cannot be run.
     *
     * <p>An unparseable priority resolves to {@link Priority#DEFAULT} rather than rejecting the
     * entry: the tier in Redis is a hint, and the authoritative value is one database read away.
     */
    public static Optional<JobStreamEntry> parse(Map<String, String> fields) {
        if (fields == null) {
            return Optional.empty();
        }
        UUID jobId;
        try {
            jobId = UUID.fromString(String.valueOf(fields.get(FIELD_JOB_ID)).trim());
        } catch (IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
        Priority priority = Priority.parse(fields.get(FIELD_PRIORITY)).orElse(Priority.DEFAULT);
        EnqueueSource source = EnqueueSource.parse(fields.get(FIELD_SOURCE)).orElse(EnqueueSource.SUBMIT);
        return Optional.of(new JobStreamEntry(jobId, priority, parseEnqueuedAt(fields), source));
    }

    private static long parseEnqueuedAt(Map<String, String> fields) {
        try {
            return Long.parseLong(String.valueOf(fields.get(FIELD_ENQUEUED_AT)).trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
