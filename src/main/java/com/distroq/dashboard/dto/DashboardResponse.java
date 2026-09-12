package com.distroq.dashboard.dto;

import com.distroq.dashboard.Section;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The eleven dashboard responses.
 *
 * <p>Every one of them starts with the same two fields. {@code timestamp} is when the server built
 * the answer, which is what the UI renders as "last updated" and what makes a frozen panel
 * visible; {@code correlationId} is the same identifier the log lines for this request carry, so
 * "the queues panel was wrong at 14:03" is a log query rather than a conversation.
 *
 * <p>Each panel arrives inside a {@link Section}, which carries its own availability. That is the
 * shape the whole design turns on: one endpoint can answer with a healthy PostgreSQL half and an
 * unavailable Redis half, and the browser can render both truthfully. A response is a 200 with an
 * unavailable section far more often than it is an error.
 *
 * <p>These schemas are stable from v1.1. Fields may be added; an existing field may not change
 * its meaning or its type.
 */
public final class DashboardResponse {

    private DashboardResponse() {
    }

    public record Overview(Instant timestamp,
                           String correlationId,
                           Section<SystemView> system,
                           Section<HealthView> health,
                           Section<QueueView.Totals> queues,
                           Section<WorkerView.Totals> workers,
                           Section<OutboxView.Totals> outbox,
                           Section<ReconciliationCounts> reconciliation,
                           Section<DlqView.Totals> deadLetters,
                           Section<List<ActivityEvent>> activity) {
    }

    /**
     * The two reconciliation numbers cheap enough to recount on every poll.
     *
     * <p>They come from {@code ReliabilityMetrics}, which counts the same predicates reconciliation
     * uses but only the ones expressible as a single indexed aggregate. The per-job cross-checks
     * are on the reconciliation page, behind the preview cache, because they are not.
     */
    public record ReconciliationCounts(long findings,
                                       long repairs,
                                       long staleScheduledJobs,
                                       long staleRetryJobs,
                                       long expiredExecutionLeases,
                                       long staleEffects,
                                       boolean autoRepairAllowed,
                                       boolean enabled) {
    }

    public record Queues(Instant timestamp,
                         String correlationId,
                         String note,
                         long throughputWindowMs,
                         Section<QueueView> queues,
                         Section<List<QueueView.ThroughputRow>> throughput) {
    }

    /**
     * Four sections, split along the dependency they need.
     *
     * <p>{@code totals} and {@code leases} come from PostgreSQL; {@code consumers} and
     * {@code pendingEntries} come from Redis. Splitting them is what lets this page keep showing
     * who owns which execution while Redis is unreachable, instead of going blank because half of
     * it could not be fetched.
     */
    public record Workers(Instant timestamp,
                          String correlationId,
                          String note,
                          long leaseWarningMs,
                          Section<WorkerView.Totals> totals,
                          Section<List<WorkerView.LeaseRow>> leases,
                          Section<List<QueueView.ConsumerRow>> consumers,
                          Section<List<WorkerView.PendingEntryRow>> pendingEntries) {
    }

    public record Outbox(Instant timestamp,
                         String correlationId,
                         Section<OutboxView> outbox) {
    }

    public record Reconciliation(Instant timestamp,
                                 String correlationId,
                                 String warning,
                                 Section<ReconciliationView> reconciliation) {
    }

    public record Jobs(Instant timestamp,
                       String correlationId,
                       Section<PageView<JobView>> jobs,
                       Section<Facets> facets) {
    }

    /** Filter options built from data that exists, so the UI does not offer an empty status. */
    public record Facets(List<String> statuses,
                         List<String> priorities,
                         List<String> jobTypes,
                         Map<String, Long> countsByStatus) {
    }

    public record JobDetail(Instant timestamp,
                            String correlationId,
                            Section<JobView.Detail> job) {
    }

    public record Dlq(Instant timestamp,
                      String correlationId,
                      String note,
                      Section<DlqView> dlq) {
    }

    public record Analytics(Instant timestamp,
                            String correlationId,
                            Section<AnalyticsView> analytics) {
    }

    public record System(Instant timestamp,
                         String correlationId,
                         Section<SystemView> system,
                         Section<HealthView> health) {
    }

    public record Activity(Instant timestamp,
                           String correlationId,
                           Section<List<ActivityEvent>> activity) {
    }
}
