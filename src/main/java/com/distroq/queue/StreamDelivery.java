package com.distroq.queue;

import com.distroq.model.Priority;

import java.util.Map;
import java.util.UUID;

/**
 * One entry handed to this consumer by {@code XREADGROUP} or {@code XAUTOCLAIM}.
 *
 * <p>Unlike v0.4's {@code Dequeued}, this is not just "what came back". The entry is still in the
 * group's Pending Entries List and stays there until {@code XACK}, so {@code streamKey} and
 * {@code entryId} are not diagnostics — they are the address the acknowledgement needs.
 *
 * <p>{@code streamPriority} is the tier the entry was <em>routed</em> at, which is not necessarily
 * the tier the job is recorded at in PostgreSQL. {@code fields} is kept whole so an entry written
 * by a future version is loggable rather than lossy.
 */
public record StreamDelivery(
        String streamKey,
        String entryId,
        UUID jobId,
        Priority streamPriority,
        Map<String, String> fields) {

    public StreamDelivery {
        fields = fields == null ? Map.of() : Map.copyOf(fields);
    }

    public String source() {
        return fields.getOrDefault(JobStreamEntry.FIELD_SOURCE, "UNKNOWN");
    }

    public boolean isFrom(EnqueueSource candidate) {
        return EnqueueSource.parse(fields.get(JobStreamEntry.FIELD_SOURCE))
                .filter(candidate::equals)
                .isPresent();
    }

    /** When this entry was written, in epoch millis; 0 when the field is missing or unreadable. */
    public long enqueuedAt() {
        try {
            return Long.parseLong(fields.getOrDefault(JobStreamEntry.FIELD_ENQUEUED_AT, "0").trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
