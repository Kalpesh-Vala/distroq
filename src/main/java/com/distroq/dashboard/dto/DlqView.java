package com.distroq.dashboard.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Dead-lettered jobs, read-only.
 *
 * <p>There is no replay field, no replay flag and no replay identifier on this view, because there
 * is no replay control on this page. Replay exists — {@code POST /api/jobs/{id}/retry} — and it
 * stays where the audit trail and the reason header already are.
 *
 * <p>{@code byDay} is built from a bounded sample of the most recent dead letters rather than from
 * a grouped query over the whole table, and {@code sampled} says so.
 */
public record DlqView(Totals totals,
                      Map<String, Long> countsByPriority,
                      Map<String, Long> countsByJobType,
                      List<DayCount> byDay,
                      boolean sampled,
                      PageView<EntryRow> entries) {

    public record Totals(long deadLettered,
                         long replayedJobs,
                         long totalReplays,
                         Instant oldestDeadLetteredAt,
                         Long oldestAgeMs) {
    }

    public record DayCount(String day, long count) {
    }

    public record EntryRow(String jobId,
                           String jobType,
                           String priority,
                           Instant movedAt,
                           int attemptCount,
                           int replayCount,
                           Instant scheduledAt,
                           String status,
                           boolean replayed,
                           String finalError) {
    }
}
