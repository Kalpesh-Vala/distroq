package com.distroq.dashboard.dto;

import java.time.Instant;
import java.util.List;

/**
 * A job as the dashboard shows it, which is a job without its payload.
 *
 * <p>{@code JobResponse} on the public API carries {@code payload} because the submitter is
 * entitled to read back what they submitted. The dashboard is a different audience with a
 * different question — it is looking at other people's jobs, in bulk, on a screen — so the field
 * simply does not exist on these records. That is stronger than redacting it: there is nothing to
 * forget to redact.
 *
 * <p>The three times a job can be waiting for are three fields and stay three fields.
 * {@code scheduledAt} is what the submitter asked for and never changes. {@code nextAttemptAt} is
 * when the next automatic retry is due. {@code startedAt} is when the current attempt began. A
 * scheduled job that has failed once has all three, and collapsing any two of them would make the
 * job's history unreadable.
 */
public record JobView(String jobId,
                      String type,
                      String priority,
                      String status,
                      Instant createdAt,
                      Instant updatedAt,
                      Instant scheduledAt,
                      Instant startedAt,
                      Instant finishedAt,
                      Instant nextAttemptAt,
                      int attemptCount,
                      int maxAttempts,
                      Integer replayCount,
                      String executionOwner,
                      Instant executionLeaseUntil,
                      Long durationMs,
                      String errorMessage) {

    /** The single-job page. Every list on it is bounded; see {@code DashboardService}. */
    public record Detail(JobView job,
                         List<TimelineEntry> timeline,
                         List<AttemptRow> attempts,
                         List<OutboxRefRow> outboxEvents,
                         List<EffectRow> effects,
                         List<LeaseHistoryRow> leaseHistory,
                         DeadLetterInfo deadLetter,
                         SchedulingInfo scheduling,
                         RetryInfo retry,
                         boolean payloadRedacted) {
    }

    /** One point on the lifecycle, chronologically ordered by the service that builds it. */
    public record TimelineEntry(String event, Instant at, String detail) {
    }

    public record AttemptRow(int attemptNumber,
                             String workerId,
                             Instant startedAt,
                             Instant finishedAt,
                             String outcome,
                             Long durationMs,
                             String errorMessage) {
    }

    /** The outbox events this job produced. Identity and status only — no payload. */
    public record OutboxRefRow(String eventId,
                               String eventType,
                               String status,
                               int attemptCount,
                               Instant createdAt,
                               Instant availableAt,
                               Instant publishedAt,
                               Instant terminalFailedAt,
                               String lastError) {
    }

    /**
     * @param responseHash the ledger stores a digest of what the effect returned, never the
     *                     response itself; this is that digest
     */
    public record EffectRow(String effectKey,
                            String effectType,
                            String status,
                            int attemptNumber,
                            String responseHash,
                            Instant createdAt,
                            Instant completedAt,
                            String errorMessage) {
    }

    /**
     * Reconciliation and operator actions recorded against this job.
     *
     * <p>The nearest thing to a lease history there is. Leases themselves are three mutable
     * columns rather than an append-only table, so what survives is the audit trail of the times
     * something intervened.
     */
    public record LeaseHistoryRow(String actionType,
                                  String actor,
                                  Instant createdAt,
                                  String beforeState,
                                  String afterState) {
    }

    public record DeadLetterInfo(boolean deadLettered,
                                 Instant movedAt,
                                 boolean replayed,
                                 Instant replayedAt,
                                 int replayCount,
                                 String finalError) {
    }

    public record SchedulingInfo(boolean scheduled,
                                 Instant scheduledAt,
                                 Long scheduleDelayMs,
                                 boolean overdue) {
    }

    public record RetryInfo(boolean retrying,
                            Instant nextAttemptAt,
                            Long dueInMs,
                            int attemptsRemaining) {
    }
}
