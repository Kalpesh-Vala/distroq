package com.distroq.dashboard;

import com.distroq.api.error.ApiException;
import com.distroq.api.error.ErrorCode;
import com.distroq.config.DistroqProperties;
import com.distroq.dashboard.dto.ActivityEvent;
import com.distroq.dashboard.dto.AnalyticsView;
import com.distroq.dashboard.dto.DashboardResponse;
import com.distroq.dashboard.dto.DlqView;
import com.distroq.dashboard.dto.JobView;
import com.distroq.dashboard.dto.OutboxView;
import com.distroq.dashboard.dto.PageView;
import com.distroq.dashboard.dto.QueueView;
import com.distroq.dashboard.dto.ReconciliationView;
import com.distroq.dashboard.dto.WorkerView;
import com.distroq.model.DeadLetter;
import com.distroq.model.Job;
import com.distroq.model.JobAttempt;
import com.distroq.model.JobEffect;
import com.distroq.model.JobStatus;
import com.distroq.model.OutboxEvent;
import com.distroq.model.OutboxStatus;
import com.distroq.model.Priority;
import com.distroq.model.ReliabilityAction;
import com.distroq.model.ReliabilityActionType;
import com.distroq.observability.Events;
import com.distroq.observability.LogFields;
import com.distroq.outbox.OutboxEventType;
import com.distroq.reliability.ReconciliationReport;
import com.distroq.reliability.ReliabilityFinding;
import com.distroq.reliability.ReliabilityMetrics;
import com.distroq.repository.DeadLetterRepository;
import com.distroq.repository.JobAttemptRepository;
import com.distroq.repository.JobEffectRepository;
import com.distroq.repository.JobRepository;
import com.distroq.repository.ReliabilityActionRepository;
import com.distroq.worker.WorkerMetrics;
import org.slf4j.MDC;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The backend-for-frontend behind {@code /api/dashboard}.
 *
 * <p>It exists so the browser does not have to. Assembling the overview from the public API would
 * take eight requests, three of which would need pagination and two of which would need the
 * administrative token; doing it here means one request, one authorization decision, one
 * correlation ID and one consistent timestamp.
 *
 * <p>Three rules hold throughout. Nothing below writes: every collaborator is a reader, and the
 * one that is not obviously a reader — the reconciliation preview — is called in the mode that
 * performs no repair and therefore no audit write. Nothing below is unbounded: every list takes a
 * page or a limit. And nothing below fails as a whole because one dependency failed: each panel is
 * wrapped in {@link Sections#read}, so a Redis outage costs the Redis panels and leaves the rest
 * standing.
 *
 * <p>No method here returns a job payload, an outbox payload or an effect response. That is not
 * enforced by filtering — the DTOs simply have no field for them, which is a guarantee that
 * survives someone editing this file.
 */
@Service
public class DashboardService {

    private static final String QUEUE_NOTE =
            "Redis Stream length includes historical acknowledged entries and is not equivalent "
                    + "to the number of jobs waiting to execute. Ready depth is the consumer "
                    + "group's lag; pending entries are delivered and unacknowledged; scheduled "
                    + "and delayed jobs are waiting on a time and are not backlog.";

    private static final String WORKER_NOTE =
            "A Redis consumer owning a delivery does not necessarily mean it owns database "
                    + "execution. Database execution ownership is controlled by the execution "
                    + "lease.";

    private static final String RECONCILIATION_WARNING =
            "The dashboard reports reconciliation findings but does not repair them.";

    private static final String DLQ_NOTE =
            "DLQ replay is available through the existing administrative API, not through this "
                    + "read-only dashboard phase.";

    /** The overview's feed is a glance, not the activity page. */
    private static final int OVERVIEW_ACTIVITY_EVENTS = 20;

    /** Ceiling on rows joined into a single job's detail page. */
    private static final int JOB_DETAIL_LIMIT = 50;

    private final DashboardProperties properties;
    private final DistroqProperties distroq;
    private final DashboardQueries queries;
    private final QueueInspector queueInspector;
    private final AnalyticsReportReader analytics;
    private final ReconciliationSnapshotCache reconciliationCache;
    private final ReadinessJournal readinessJournal;
    private final RuntimeInfo runtimeInfo;
    private final HealthProbe healthProbe;
    private final ReliabilityMetrics reliabilityMetrics;
    private final WorkerMetrics workerMetrics;
    private final JobRepository jobs;
    private final JobAttemptRepository attempts;
    private final JobEffectRepository effects;
    private final DeadLetterRepository deadLetters;
    private final ReliabilityActionRepository reliabilityActions;

    @SuppressWarnings("java:S107") // a backend-for-frontend aggregates; each collaborator is used
    public DashboardService(DashboardProperties properties,
                            DistroqProperties distroq,
                            DashboardQueries queries,
                            QueueInspector queueInspector,
                            AnalyticsReportReader analytics,
                            ReconciliationSnapshotCache reconciliationCache,
                            ReadinessJournal readinessJournal,
                            RuntimeInfo runtimeInfo,
                            HealthProbe healthProbe,
                            ReliabilityMetrics reliabilityMetrics,
                            WorkerMetrics workerMetrics,
                            JobRepository jobs,
                            JobAttemptRepository attempts,
                            JobEffectRepository effects,
                            DeadLetterRepository deadLetters,
                            ReliabilityActionRepository reliabilityActions) {
        this.properties = properties;
        this.distroq = distroq;
        this.queries = queries;
        this.queueInspector = queueInspector;
        this.analytics = analytics;
        this.reconciliationCache = reconciliationCache;
        this.readinessJournal = readinessJournal;
        this.runtimeInfo = runtimeInfo;
        this.healthProbe = healthProbe;
        this.reliabilityMetrics = reliabilityMetrics;
        this.workerMetrics = workerMetrics;
        this.jobs = jobs;
        this.attempts = attempts;
        this.effects = effects;
        this.deadLetters = deadLetters;
        this.reliabilityActions = reliabilityActions;
    }

    // ---------------------------------------------------------------------------- overview

    public DashboardResponse.Overview overview() {
        return new DashboardResponse.Overview(
                Instant.now(),
                correlationId(),
                Sections.read("system", runtimeInfo::describe),
                Sections.read("health", healthProbe::probe),
                Sections.read("queues", this::queueTotals),
                Sections.read("workers", this::workerTotals),
                Sections.read("outbox", this::outboxTotals),
                Sections.read("reconciliation", this::reconciliationCounts),
                Sections.read("deadLetters", this::dlqTotals),
                Sections.read("activity", () -> activityEvents(OVERVIEW_ACTIVITY_EVENTS)));
    }

    // ------------------------------------------------------------------------------ queues

    public DashboardResponse.Queues queues() {
        return new DashboardResponse.Queues(
                Instant.now(),
                correlationId(),
                QUEUE_NOTE,
                properties.recentWindowMs(),
                Sections.read("queues", this::queueView),
                Sections.read("throughput", this::throughput));
    }

    private QueueView queueView() {
        List<QueueInspector.StreamSnapshot> streams = queueInspector.streams();
        QueueInspector.SortedSetSnapshot scheduled = queueInspector.scheduled();
        QueueInspector.SortedSetSnapshot delayed = queueInspector.delayed();

        List<QueueView.PriorityRow> rows = new ArrayList<>(streams.size());
        for (QueueInspector.StreamSnapshot stream : streams) {
            rows.add(new QueueView.PriorityRow(
                    stream.priority(),
                    stream.streamKey(),
                    stream.streamLength(),
                    stream.readyDepth(),
                    stream.pendingEntries(),
                    scheduled.byPriority().getOrDefault(stream.priority(), 0L),
                    delayed.byPriority().getOrDefault(stream.priority(), 0L),
                    stream.oldestPendingEntryAgeMs(),
                    stream.consumers().size(),
                    consumerRows(stream)));
        }
        return new QueueView(rows, totals(streams, scheduled, delayed));
    }

    private static List<QueueView.ConsumerRow> consumerRows(QueueInspector.StreamSnapshot stream) {
        return stream.consumers().stream()
                .map(consumer -> new QueueView.ConsumerRow(consumer.consumerName(),
                        consumer.streamKey(), consumer.priority(), consumer.pendingCount(),
                        consumer.idleTimeMs(), consumer.pendingCount() == 0))
                .toList();
    }

    private static QueueView.Totals totals(List<QueueInspector.StreamSnapshot> streams,
                                           QueueInspector.SortedSetSnapshot scheduled,
                                           QueueInspector.SortedSetSnapshot delayed) {
        Map<String, Long> streamDepth = new LinkedHashMap<>();
        Map<String, Long> readyDepth = new LinkedHashMap<>();
        Map<String, Long> pending = new LinkedHashMap<>();
        long streamTotal = 0;
        long pendingTotal = 0;
        Long readyTotal = 0L;
        int consumers = 0;

        for (QueueInspector.StreamSnapshot stream : streams) {
            streamDepth.put(stream.priority(), stream.streamLength());
            readyDepth.put(stream.priority(), stream.readyDepth());
            pending.put(stream.priority(), stream.pendingEntries());
            streamTotal += stream.streamLength();
            pendingTotal += stream.pendingEntries();
            // one unknowable lag makes the total unknowable; a partial sum would read as a backlog
            readyTotal = (readyTotal == null || stream.readyDepth() == null)
                    ? null : readyTotal + stream.readyDepth();
            consumers += stream.consumers().size();
        }

        return new QueueView.Totals(streamDepth, readyDepth, pending, scheduled.byPriority(),
                delayed.byPriority(), streamTotal, readyTotal, pendingTotal, scheduled.total(),
                delayed.total(), consumers);
    }

    private QueueView.Totals queueTotals() {
        return queueView().totals();
    }

    private List<QueueView.ThroughputRow> throughput() {
        Instant since = Instant.now().minusMillis(properties.recentWindowMs());
        return queries.throughputByPriority(since).values().stream()
                .map(row -> new QueueView.ThroughputRow(row.priority(), row.submitted(),
                        row.started(), row.succeeded(), row.deadLettered(), row.successRate()))
                .toList();
    }

    // ----------------------------------------------------------------------------- workers

    public DashboardResponse.Workers workers() {
        // Redis is read once for both Redis-backed panels, and its per-consumer idle times are
        // what let a lease row say whether the two systems still agree about that worker
        List<QueueView.ConsumerRow> consumers = null;
        List<WorkerView.PendingEntryRow> pending = null;
        RuntimeException redisFailure = null;
        try {
            consumers = consumerRows();
            pending = pendingEntryRows();
        } catch (RuntimeException e) {
            redisFailure = e;
        }

        Map<String, Long> idleByConsumer = new LinkedHashMap<>();
        if (consumers != null) {
            consumers.forEach(consumer ->
                    idleByConsumer.merge(consumer.consumerName(), consumer.idleTimeMs(), Math::min));
        }
        boolean redisAvailable = redisFailure == null;

        return new DashboardResponse.Workers(
                Instant.now(),
                correlationId(),
                WORKER_NOTE,
                properties.leaseWarningMs(),
                Sections.read("workerTotals", this::workerTotals),
                Sections.read("leases", () -> leaseRows(idleByConsumer, redisAvailable)),
                redisAvailable ? Section.available(consumers) : Section.unavailable(redisFailure),
                redisAvailable ? Section.available(pending) : Section.unavailable(redisFailure));
    }

    private List<QueueView.ConsumerRow> consumerRows() {
        List<QueueView.ConsumerRow> consumers = new ArrayList<>();
        queueInspector.streams().forEach(stream -> consumers.addAll(consumerRows(stream)));
        return List.copyOf(consumers);
    }

    private List<WorkerView.PendingEntryRow> pendingEntryRows() {
        return queueInspector.pendingEntries(QueueInspector.MAX_PENDING_DETAIL).stream()
                .map(entry -> new WorkerView.PendingEntryRow(entry.entryId(), entry.consumerName(),
                        entry.priority(), entry.idleMs(), entry.deliveryCount(), entry.ageMs(),
                        entry.deliveryCount() > 1))
                .toList();
    }

    private List<WorkerView.LeaseRow> leaseRows(Map<String, Long> idleByConsumer,
                                                boolean redisAvailable) {
        Instant now = Instant.now();
        long leaseMs = distroq.worker().executionLeaseMs();
        return queries.activeLeases(now, properties.maxPageSize()).stream()
                .map(job -> leaseRow(job, now, leaseMs,
                        redisAvailable ? idleByConsumer.get(job.getExecutionOwner()) : null,
                        redisAvailable))
                .toList();
    }

    private WorkerView.LeaseRow leaseRow(Job job, Instant now, long leaseMs, Long consumerIdleMs,
                                         boolean redisAvailable) {
        Instant leaseUntil = job.getExecutionLeaseUntil();
        long remaining = leaseUntil == null ? 0L : Duration.between(now, leaseUntil).toMillis();
        // null rather than false when Redis is unreachable: "the consumer is fine" and "we could
        // not ask" are different answers and the UI renders them differently
        Boolean heartbeatStale = redisAvailable
                ? Boolean.valueOf(consumerIdleMs != null && consumerIdleMs > leaseMs) : null;
        return new WorkerView.LeaseRow(
                String.valueOf(job.getId()),
                job.getActiveAttemptId() == null ? null : job.getActiveAttemptId().toString(),
                job.getExecutionOwner(),
                job.getExecutionOwner(),
                job.getPriority().name(),
                job.getType(),
                job.getStatus().name(),
                job.getStartedAt(),
                leaseUntil,
                remaining,
                remaining > 0 && remaining <= properties.leaseWarningMs(),
                remaining <= 0,
                heartbeatStale,
                job.getAttemptCount(),
                job.getMaxAttempts());
    }

    private WorkerView.Totals workerTotals() {
        int concurrency = workerMetrics.concurrency();
        int active = workerMetrics.activeWorkers();
        return new WorkerView.Totals(concurrency, active, Math.max(0, concurrency - active),
                queries.countActiveLeases(Instant.now()), workerMetrics.reclaimedEntries(),
                queries.countAbandonedAttempts(), true);
    }

    // ------------------------------------------------------------------------------ outbox

    public DashboardResponse.Outbox outbox(OutboxStatus status, OutboxEventType eventType,
                                           UUID aggregateId, Integer page, Integer size,
                                           String sort, String direction) {
        return new DashboardResponse.Outbox(
                Instant.now(),
                correlationId(),
                Sections.read("outbox", () -> outboxView(status, eventType, aggregateId,
                        page, size, sort, direction)));
    }

    private OutboxView outboxView(OutboxStatus status, OutboxEventType eventType, UUID aggregateId,
                                  Integer page, Integer size, String sort, String direction) {
        Instant now = Instant.now();
        int pageNumber = properties.pageNumber(page);
        int pageSize = properties.pageSize(size);

        DashboardQueries.Slice<OutboxEvent> slice = queries.outboxEvents(status, eventType,
                aggregateId, pageNumber, pageSize, sort, ascending(direction));

        List<OutboxView.EventRow> rows = slice.content().stream()
                .map(event -> outboxRow(event, now))
                .toList();

        List<Long> latencies = queries.recentPublicationLatenciesMs();
        return new OutboxView(
                outboxTotals(),
                queries.outboxCountsByStatus(),
                queries.outboxCountsByEventType(),
                queries.outboxUnpublishedByAge(now),
                new OutboxView.Latency(percentile(latencies, 50), percentile(latencies, 95),
                        latencies.size(), true),
                PageView.of(rows, pageNumber, pageSize, slice.totalElements()));
    }

    private static OutboxView.EventRow outboxRow(OutboxEvent event, Instant now) {
        return new OutboxView.EventRow(
                event.getId().toString(),
                event.getEventType().name(),
                event.getAggregateId() == null ? null : event.getAggregateId().toString(),
                event.getStatus().name(),
                event.getAttemptCount(),
                event.getOperatorRetryCount(),
                event.getCreatedAt(),
                event.getAvailableAt(),
                event.getLockedUntil(),
                event.getPublishedAt(),
                event.getTerminalFailedAt(),
                Redaction.error(event.getLastError()),
                Math.max(0L, Duration.between(event.getCreatedAt(), now).toMillis()),
                true);
    }

    private OutboxView.Totals outboxTotals() {
        Instant now = Instant.now();
        Map<String, Long> byStatus = queries.outboxCountsByStatus();
        Instant oldest = queries.oldestUnpublishedCreatedAt();
        return new OutboxView.Totals(
                byStatus.getOrDefault(OutboxStatus.PENDING.name(), 0L),
                byStatus.getOrDefault(OutboxStatus.PUBLISHING.name(), 0L),
                byStatus.getOrDefault(OutboxStatus.PUBLISHED.name(), 0L),
                // PENDING with at least one attempt behind it: still inside its budget, and the
                // one outbox count that is not derivable from the status column alone
                asLong(reliabilityMetrics.snapshot(now).get("outboxRetryableFailed")),
                byStatus.getOrDefault(OutboxStatus.FAILED.name(), 0L),
                oldest == null ? null : Math.max(0L, Duration.between(oldest, now).toMillis()),
                oldest,
                queries.outboxOperatorRetries());
    }

    /**
     * Nearest-rank on a sorted sample. Not an estimator and not a sketch: the sample is at most a
     * few hundred values and already in memory, so the exact rank of the sample is both cheaper
     * and easier to explain than an approximation of the population.
     */
    static Long percentile(List<Long> values, int percentile) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        List<Long> sorted = values.stream().sorted().toList();
        int rank = (int) Math.ceil(percentile / 100.0 * sorted.size());
        return sorted.get(Math.clamp(rank - 1, 0, sorted.size() - 1));
    }

    // ---------------------------------------------------------------------- reconciliation

    public DashboardResponse.Reconciliation reconciliation() {
        return new DashboardResponse.Reconciliation(
                Instant.now(),
                correlationId(),
                RECONCILIATION_WARNING,
                Sections.read("reconciliation", this::reconciliationView));
    }

    private ReconciliationView reconciliationView() {
        ReconciliationSnapshotCache.Snapshot snapshot = reconciliationCache.get();
        ReconciliationReport report = snapshot.report();
        DistroqProperties.Reconciliation config = distroq.reconciliation();

        List<ReconciliationView.FindingRow> findings = report.findings().stream()
                .map(DashboardService::findingRow)
                .toList();

        Map<String, Long> bySeverity = new LinkedHashMap<>();
        for (FindingSeverity severity : FindingSeverity.values()) {
            bySeverity.put(severity.name(), 0L);
        }
        Map<String, Long> byCategory = new LinkedHashMap<>();
        for (com.distroq.reliability.FindingCategory category
                : com.distroq.reliability.FindingCategory.values()) {
            byCategory.put(category.name(), 0L);
        }
        for (ReliabilityFinding finding : report.findings()) {
            bySeverity.merge(FindingSeverity.of(finding.type()).name(), 1L, Long::sum);
            byCategory.merge(finding.category().name(), 1L, Long::sum);
        }

        return new ReconciliationView(
                new ReconciliationView.Summary(
                        report.startedAt(),
                        report.finishedAt(),
                        Duration.between(report.startedAt(), report.finishedAt()).toMillis(),
                        report.inspected(),
                        report.batchSize(),
                        report.inspected() >= report.batchSize(),
                        report.findingCount(),
                        report.repaired(),
                        report.skipped(),
                        report.failed(),
                        report.unresolved(),
                        report.skippedBecauseAnotherRunHoldsTheLock()),
                new ReconciliationView.Configuration(config.enabled(), config.autoRepair(),
                        config.batchSize(), config.pollIntervalMs(), config.staleScheduledAfterMs(),
                        config.staleOutboxAfterMs(), config.staleLeaseAfterMs(),
                        config.requeueFailedOutbox()),
                report.countsByType(),
                byCategory,
                bySeverity,
                findings,
                snapshot.generatedAt(),
                true);
    }

    private static ReconciliationView.FindingRow findingRow(ReliabilityFinding finding) {
        return new ReconciliationView.FindingRow(
                finding.type().name(),
                finding.category().name(),
                FindingSeverity.of(finding.type()).name(),
                finding.targetType(),
                finding.targetId(),
                finding.detail(),
                finding.type().autoRepairable(),
                finding.resolution().name());
    }

    private DashboardResponse.ReconciliationCounts reconciliationCounts() {
        Map<String, Object> snapshot = reliabilityMetrics.snapshot(Instant.now());
        DistroqProperties.Reconciliation config = distroq.reconciliation();
        return new DashboardResponse.ReconciliationCounts(
                asLong(snapshot.get("reconciliationFindings")),
                asLong(snapshot.get("reconciliationRepairs")),
                asLong(snapshot.get("staleScheduledJobs")),
                asLong(snapshot.get("staleRetryJobs")),
                asLong(snapshot.get("expiredExecutionLeases")),
                asLong(snapshot.get("staleEffects")),
                config.autoRepair(),
                config.enabled());
    }

    // -------------------------------------------------------------------------------- jobs

    @SuppressWarnings("java:S107") // the filter set the UI offers, one parameter each
    public DashboardResponse.Jobs jobs(JobStatus status, Priority priority, String jobType,
                                       Instant createdAfter, Instant createdBefore,
                                       Boolean scheduled, Boolean hasAttempts, UUID jobId,
                                       Integer page, Integer size, String sort, String direction) {
        int pageNumber = properties.pageNumber(page);
        int pageSize = properties.pageSize(size);
        DashboardQueries.JobFilter filter = new DashboardQueries.JobFilter(status, priority,
                jobType, createdAfter, createdBefore, scheduled, hasAttempts, jobId);

        return new DashboardResponse.Jobs(
                Instant.now(),
                correlationId(),
                Sections.read("jobs", () -> {
                    DashboardQueries.Slice<Job> slice = queries.jobs(filter, pageNumber, pageSize,
                            sort, ascending(direction));
                    return PageView.of(slice.content().stream().map(DashboardService::jobRow).toList(),
                            pageNumber, pageSize, slice.totalElements());
                }),
                Sections.read("facets", this::facets));
    }

    private DashboardResponse.Facets facets() {
        return new DashboardResponse.Facets(
                java.util.Arrays.stream(JobStatus.values()).map(Enum::name).toList(),
                Priority.STRICT_ORDER.stream().map(Enum::name).toList(),
                queries.jobTypes(properties.maxPageSize()),
                queries.jobCountsByStatus());
    }

    private static JobView jobRow(Job job) {
        return jobRow(job, null);
    }

    private static JobView jobRow(Job job, Integer replayCount) {
        Instant started = job.getStartedAt();
        Instant finished = job.getFinishedAt();
        return new JobView(
                job.getId().toString(),
                job.getType(),
                job.getPriority().name(),
                job.getStatus().name(),
                job.getCreatedAt(),
                job.getUpdatedAt(),
                job.getScheduledAt(),
                started,
                finished,
                job.getNextAttemptAt(),
                job.getAttemptCount(),
                job.getMaxAttempts(),
                replayCount,
                job.getExecutionOwner(),
                job.getExecutionLeaseUntil(),
                started == null || finished == null ? null
                        : Duration.between(started, finished).toMillis(),
                Redaction.error(job.getErrorMessage()));
    }

    public DashboardResponse.JobDetail job(UUID jobId) {
        Job job = jobs.findById(jobId)
                .orElseThrow(() -> new ApiException(ErrorCode.JOB_NOT_FOUND,
                        "No job with id " + jobId));
        return new DashboardResponse.JobDetail(
                Instant.now(),
                correlationId(),
                Sections.read("job", () -> detail(job)));
    }

    private JobView.Detail detail(Job job) {
        Instant now = Instant.now();
        List<JobAttempt> attemptRows = attempts.findByJobIdOrderByAttemptNumberAsc(job.getId());
        List<JobEffect> effectRows = effects.findByJobIdOrderByCreatedAtAsc(job.getId());
        List<OutboxEvent> events = queries.outboxEventsForJob(job.getId(), JOB_DETAIL_LIMIT);
        Optional<DeadLetter> deadLetter = deadLetters.findById(job.getId());
        List<ReliabilityAction> actions = reliabilityActions
                .findByTargetIdOrderByCreatedAtDesc(job.getId(),
                        PageRequest.of(0, JOB_DETAIL_LIMIT))
                .getContent();

        return new JobView.Detail(
                jobRow(job, deadLetter.map(DeadLetter::getReplayCount).orElse(null)),
                timeline(job, attemptRows, events, deadLetter.orElse(null)),
                attemptRows.stream().map(DashboardService::attemptRow).toList(),
                events.stream().map(DashboardService::outboxRefRow).toList(),
                effectRows.stream().map(DashboardService::effectRow).toList(),
                actions.stream().map(DashboardService::leaseHistoryRow).toList(),
                deadLetterInfo(deadLetter.orElse(null)),
                schedulingInfo(job, now),
                retryInfo(job, now),
                true);
    }

    /**
     * The job's history in the order it happened.
     *
     * <p>Assembled from timestamps that already exist rather than from an event table, which is
     * why the four kinds of "when" stay distinguishable: {@code scheduledAt} is the time the
     * submitter asked for, {@code nextAttemptAt} is when the next automatic retry is due, an
     * attempt's {@code startedAt} is when an execution actually began, and an outbox event's
     * {@code publishedAt} is when the intent reached Redis. Collapsing any of them would produce
     * a timeline that reads plausibly and is wrong.
     */
    private static List<JobView.TimelineEntry> timeline(Job job, List<JobAttempt> attemptRows,
                                                        List<OutboxEvent> events,
                                                        DeadLetter deadLetter) {
        List<JobView.TimelineEntry> entries = new ArrayList<>();
        entries.add(new JobView.TimelineEntry(Events.JOB_SUBMITTED, job.getCreatedAt(),
                job.getPriority().name() + " " + job.getType()));
        if (job.getScheduledAt() != null) {
            entries.add(new JobView.TimelineEntry(Events.JOB_SCHEDULED, job.getScheduledAt(),
                    "requested execution time"));
        }
        for (OutboxEvent event : events) {
            if (event.getPublishedAt() != null) {
                entries.add(new JobView.TimelineEntry(Events.OUTBOX_PUBLISHED,
                        event.getPublishedAt(), event.getEventType().name()));
            }
            if (event.getTerminalFailedAt() != null) {
                entries.add(new JobView.TimelineEntry(Events.OUTBOX_FAILED,
                        event.getTerminalFailedAt(), event.getEventType().name()
                        + " after " + event.getAttemptCount() + " attempt(s)"));
            }
        }
        for (JobAttempt attempt : attemptRows) {
            entries.add(new JobView.TimelineEntry(Events.JOB_STARTED, attempt.getStartedAt(),
                    "attempt " + attempt.getAttemptNumber() + " on " + attempt.getWorkerId()));
            if (attempt.getFinishedAt() == null) {
                continue;
            }
            entries.add(new JobView.TimelineEntry(switch (attempt.getOutcome()) {
                case SUCCESS -> Events.JOB_SUCCEEDED;
                case ABANDONED -> Events.JOB_RECLAIMED;
                default -> Events.JOB_RETRY_SCHEDULED;
            }, attempt.getFinishedAt(), "attempt " + attempt.getAttemptNumber() + " "
                    + attempt.getOutcome().name()));
        }
        if (deadLetter != null) {
            entries.add(new JobView.TimelineEntry(Events.JOB_DEAD_LETTERED, deadLetter.getMovedAt(),
                    "moved to the dead-letter queue"));
            if (deadLetter.getReplayedAt() != null) {
                entries.add(new JobView.TimelineEntry(Events.JOB_REPLAYED,
                        deadLetter.getReplayedAt(), "replay " + deadLetter.getReplayCount()));
            }
        }
        entries.removeIf(entry -> entry.at() == null);
        entries.sort(Comparator.comparing(JobView.TimelineEntry::at));
        return List.copyOf(entries);
    }

    private static JobView.AttemptRow attemptRow(JobAttempt attempt) {
        Instant started = attempt.getStartedAt();
        Instant finished = attempt.getFinishedAt();
        return new JobView.AttemptRow(attempt.getAttemptNumber(), attempt.getWorkerId(), started,
                finished, attempt.getOutcome().name(),
                started == null || finished == null ? null
                        : Duration.between(started, finished).toMillis(),
                Redaction.error(attempt.getErrorMessage()));
    }

    private static JobView.OutboxRefRow outboxRefRow(OutboxEvent event) {
        return new JobView.OutboxRefRow(event.getId().toString(), event.getEventType().name(),
                event.getStatus().name(), event.getAttemptCount(), event.getCreatedAt(),
                event.getAvailableAt(), event.getPublishedAt(), event.getTerminalFailedAt(),
                Redaction.error(event.getLastError()));
    }

    private static JobView.EffectRow effectRow(JobEffect effect) {
        return new JobView.EffectRow(effect.getEffectKey(), effect.getEffectType(),
                effect.getStatus().name(), effect.getAttemptNumber(), effect.getResponseHash(),
                effect.getCreatedAt(), effect.getCompletedAt(),
                Redaction.error(effect.getErrorMessage()));
    }

    private static JobView.LeaseHistoryRow leaseHistoryRow(ReliabilityAction action) {
        return new JobView.LeaseHistoryRow(action.getActionType().name(), action.getActor(),
                action.getCreatedAt(), action.getBeforeState(), action.getAfterState());
    }

    private static JobView.DeadLetterInfo deadLetterInfo(DeadLetter deadLetter) {
        if (deadLetter == null) {
            return new JobView.DeadLetterInfo(false, null, false, null, 0, null);
        }
        return new JobView.DeadLetterInfo(true, deadLetter.getMovedAt(), deadLetter.isReplayed(),
                deadLetter.getReplayedAt(), deadLetter.getReplayCount(),
                Redaction.error(deadLetter.getFinalError()));
    }

    private static JobView.SchedulingInfo schedulingInfo(Job job, Instant now) {
        Instant scheduledAt = job.getScheduledAt();
        if (scheduledAt == null) {
            return new JobView.SchedulingInfo(false, null, null, false);
        }
        Long delay = job.getStartedAt() == null ? null
                : Duration.between(scheduledAt, job.getStartedAt()).toMillis();
        return new JobView.SchedulingInfo(true, scheduledAt, delay,
                job.getStatus() == JobStatus.SCHEDULED && scheduledAt.isBefore(now));
    }

    private static JobView.RetryInfo retryInfo(Job job, Instant now) {
        Instant next = job.getNextAttemptAt();
        return new JobView.RetryInfo(job.getStatus() == JobStatus.RETRYING, next,
                next == null ? null : Duration.between(now, next).toMillis(),
                Math.max(0, job.getMaxAttempts() - job.getAttemptCount()));
    }

    // --------------------------------------------------------------------------------- dlq

    public DashboardResponse.Dlq dlq(Boolean replayed, Integer page, Integer size) {
        return new DashboardResponse.Dlq(
                Instant.now(),
                correlationId(),
                DLQ_NOTE,
                Sections.read("dlq", () -> dlqView(replayed, page, size)));
    }

    private DlqView dlqView(Boolean replayed, Integer page, Integer size) {
        int pageNumber = properties.pageNumber(page);
        int pageSize = properties.pageSize(size);
        List<DashboardQueries.DeadLetterFact> sample =
                queries.deadLetterFacts(DashboardQueries.DEAD_LETTER_SAMPLE_SIZE);

        Map<String, Long> byPriority = new LinkedHashMap<>();
        for (Priority priority : Priority.STRICT_ORDER) {
            byPriority.put(priority.name(), 0L);
        }
        Map<String, Long> byJobType = new LinkedHashMap<>();
        Map<String, Long> byDay = new LinkedHashMap<>();
        for (DashboardQueries.DeadLetterFact fact : sample) {
            byPriority.merge(fact.priority(), 1L, Long::sum);
            byJobType.merge(fact.jobType(), 1L, Long::sum);
            byDay.merge(day(fact.movedAt()), 1L, Long::sum);
        }

        DashboardQueries.Slice<DeadLetter> slice = queries.deadLetters(replayed, pageNumber, pageSize);
        Map<UUID, DashboardQueries.DeadLetterFact> factsById = new LinkedHashMap<>();
        sample.forEach(fact -> factsById.put(fact.jobId(), fact));

        List<DlqView.EntryRow> rows = slice.content().stream()
                .map(letter -> entryRow(letter, factsById.get(letter.getJobId())))
                .toList();

        return new DlqView(dlqTotals(), byPriority, byJobType,
                byDay.entrySet().stream()
                        .map(entry -> new DlqView.DayCount(entry.getKey(), entry.getValue()))
                        .sorted(Comparator.comparing(DlqView.DayCount::day))
                        .toList(),
                sample.size() >= DashboardQueries.DEAD_LETTER_SAMPLE_SIZE,
                PageView.of(rows, pageNumber, pageSize, slice.totalElements()));
    }

    private static DlqView.EntryRow entryRow(DeadLetter letter,
                                             DashboardQueries.DeadLetterFact fact) {
        return new DlqView.EntryRow(
                letter.getJobId().toString(),
                fact == null ? null : fact.jobType(),
                fact == null ? null : fact.priority(),
                letter.getMovedAt(),
                fact == null ? 0 : fact.attemptCount(),
                letter.getReplayCount(),
                fact == null ? null : fact.scheduledAt(),
                fact == null ? null : fact.status(),
                letter.isReplayed(),
                Redaction.error(letter.getFinalError()));
    }

    private DlqView.Totals dlqTotals() {
        Instant oldest = queries.oldestDeadLetteredAt();
        return new DlqView.Totals(
                queries.countDeadLetters(false),
                queries.countDeadLetters(true),
                queries.totalReplays(),
                oldest,
                oldest == null ? null
                        : Math.max(0L, Duration.between(oldest, Instant.now()).toMillis()));
    }

    // --------------------------------------------------------------------------- analytics

    public DashboardResponse.Analytics analytics(String run, String startDate, String endDate,
                                                 String priority, String jobType) {
        return new DashboardResponse.Analytics(
                Instant.now(),
                correlationId(),
                analyticsSection(run, startDate, endDate, priority, jobType));
    }

    /**
     * Three outcomes, deliberately distinct. An export that exists is available and carries the
     * time it was generated as its last-updated stamp; a deployment that has never run the
     * pipeline is NOT_CONFIGURED and gets an empty state rather than an error; a directory that
     * cannot be read is UNAVAILABLE. Rendering the second as an empty chart would be a lie about
     * throughput.
     */
    private Section<AnalyticsView> analyticsSection(String run, String startDate, String endDate,
                                                    String priority, String jobType) {
        try {
            AnalyticsView view = analyticsView(run, startDate, endDate, priority, jobType);
            if (view == null) {
                return Section.notConfigured("No analytics export is available for this "
                        + "deployment; run the v0.9 pipeline to populate this page");
            }
            return Section.available(view,
                    view.generatedAt() == null ? Instant.now() : view.generatedAt());
        } catch (RuntimeException e) {
            return Section.unavailable(e);
        }
    }

    private AnalyticsView analyticsView(String run, String startDate, String endDate,
                                        String priority, String jobType) {
        Optional<AnalyticsReportReader.AnalyticsRun> loaded = analytics.read(run);
        if (loaded.isEmpty()) {
            return null;
        }
        AnalyticsReportReader.AnalyticsRun source = loaded.get();
        LocalDate from = parseDate(startDate);
        LocalDate to = parseDate(endDate);

        Map<String, List<Map<String, Object>>> filtered = new LinkedHashMap<>();
        source.reports().forEach((name, rows) ->
                filtered.put(name, rows.stream()
                        .filter(row -> withinDays(row, from, to))
                        .filter(row -> matches(row, "priority", priority))
                        .filter(row -> matches(row, "job_type", jobType))
                        .toList()));

        return new AnalyticsView(source.runId(), analytics.availableRuns(), source.windowStart(),
                source.windowEnd(), source.generatedAt(), source.exportedAt(),
                source.analyticsVersion(), source.applicationVersion(), source.durationSeconds(),
                false, source.exact(),
                "Batch aggregates from the v0.9 analytics export. Historical, not live.",
                source.headline(), source.factRowCounts(), source.dataQuality(), filtered,
                new AnalyticsView.Filters(startDate, endDate, priority, jobType));
    }

    private static boolean withinDays(Map<String, Object> row, LocalDate from, LocalDate to) {
        if (from == null && to == null) {
            return true;
        }
        LocalDate day = parseDate(String.valueOf(row.get("day")));
        if (day == null) {
            // a report without a day column is a whole-window summary and is never date-filtered
            return !row.containsKey("day");
        }
        return (from == null || !day.isBefore(from)) && (to == null || !day.isAfter(to));
    }

    private static boolean matches(Map<String, Object> row, String column, String wanted) {
        if (wanted == null || wanted.isBlank() || !row.containsKey(column)) {
            return true;
        }
        return wanted.equalsIgnoreCase(String.valueOf(row.get(column)));
    }

    private static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank() || "null".equals(raw)) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------------------ system

    public DashboardResponse.System system() {
        return new DashboardResponse.System(
                Instant.now(),
                correlationId(),
                Sections.read("system", runtimeInfo::describe),
                Sections.read("health", healthProbe::probe));
    }

    // ---------------------------------------------------------------------------- activity

    public DashboardResponse.Activity activity(Integer limit) {
        int bounded = limit == null ? properties.maxActivityEvents()
                : Math.clamp(limit, 1, properties.maxActivityEvents());
        return new DashboardResponse.Activity(
                Instant.now(),
                correlationId(),
                Sections.read("activity", () -> activityEvents(bounded)));
    }

    /**
     * Reconstructed from database rows, capped, newest first.
     *
     * <p>Each source is a bounded query on an indexed timestamp, so the cost is the same whether
     * the tables hold a thousand rows or ten million. Readiness transitions come from an in-process
     * ring instead, because nothing writes them down; see {@link ReadinessJournal}.
     */
    private List<ActivityEvent> activityEvents(int limit) {
        List<ActivityEvent> events = new ArrayList<>();

        readinessJournal.recent().forEach(transition -> events.add(new ActivityEvent(
                transition.event(), transition.at(), null, null, null, null, transition.state(),
                "this instance")));

        for (Job job : queries.recentlyChangedJobs(limit)) {
            String jobId = job.getId().toString();
            events.add(new ActivityEvent(Events.JOB_SUBMITTED, job.getCreatedAt(), jobId, null,
                    job.getType(), job.getPriority().name(), job.getStatus().name(), null));
            if (job.getStartedAt() != null) {
                events.add(new ActivityEvent(Events.JOB_STARTED, job.getStartedAt(), jobId, null,
                        job.getType(), job.getPriority().name(), job.getStatus().name(),
                        "attempt " + job.getAttemptCount()));
            }
            if (job.getStatus() == JobStatus.SUCCEEDED && job.getFinishedAt() != null) {
                events.add(new ActivityEvent(Events.JOB_SUCCEEDED, job.getFinishedAt(), jobId, null,
                        job.getType(), job.getPriority().name(), job.getStatus().name(), null));
            }
            if (job.getStatus() == JobStatus.DEAD_LETTERED && job.getFinishedAt() != null) {
                events.add(new ActivityEvent(Events.JOB_DEAD_LETTERED, job.getFinishedAt(), jobId,
                        null, job.getType(), job.getPriority().name(), job.getStatus().name(),
                        "after " + job.getAttemptCount() + " attempt(s)"));
            }
            if (job.getStatus() == JobStatus.RETRYING && job.getNextAttemptAt() != null) {
                events.add(new ActivityEvent(Events.JOB_RETRY_SCHEDULED, job.getUpdatedAt(), jobId,
                        null, job.getType(), job.getPriority().name(), job.getStatus().name(),
                        "next attempt at " + job.getNextAttemptAt()));
            }
        }

        for (OutboxEvent event : queries.recentOutboxEvents(limit)) {
            String aggregate = event.getAggregateId() == null ? null
                    : event.getAggregateId().toString();
            if (event.getPublishedAt() != null) {
                events.add(new ActivityEvent(Events.OUTBOX_PUBLISHED, event.getPublishedAt(),
                        aggregate, event.getId().toString(), null, null,
                        event.getEventType().name(), null));
            }
            if (event.getTerminalFailedAt() != null) {
                events.add(new ActivityEvent(Events.OUTBOX_FAILED, event.getTerminalFailedAt(),
                        aggregate, event.getId().toString(), null, null,
                        event.getEventType().name(),
                        "after " + event.getAttemptCount() + " attempt(s)"));
            }
        }

        for (DashboardQueries.AbandonedAttempt abandoned : queries.recentAbandonedAttempts(limit)) {
            events.add(new ActivityEvent(Events.JOB_RECLAIMED, abandoned.finishedAt(),
                    abandoned.jobId().toString(), null, null, null, "ABANDONED",
                    "attempt " + abandoned.attemptNumber() + " on " + abandoned.workerId()));
        }

        for (DeadLetter letter : queries.recentDeadLetters(limit)) {
            if (letter.isReplayed() && letter.getReplayedAt() != null) {
                events.add(new ActivityEvent(Events.JOB_REPLAYED, letter.getReplayedAt(),
                        letter.getJobId().toString(), null, null, null, "REPLAYED",
                        "replay " + letter.getReplayCount()));
            }
        }

        for (ReliabilityAction action : queries.recentReliabilityActions(limit)) {
            boolean repair = action.getActionType() != ReliabilityActionType.OUTBOX_CLEANUP;
            events.add(new ActivityEvent(
                    repair ? Events.RECONCILIATION_REPAIR : Events.RECONCILIATION_FINDING,
                    action.getCreatedAt(),
                    action.getTargetId() == null ? null : action.getTargetId().toString(), null,
                    null, null, action.getActionType().name(), action.getTargetType()));
        }

        events.removeIf(event -> event.at() == null);
        events.sort(Comparator.comparing(ActivityEvent::at).reversed());
        return events.size() <= limit ? List.copyOf(events) : List.copyOf(events.subList(0, limit));
    }

    // ------------------------------------------------------------------------------ shared

    private static String day(Instant at) {
        return at.atZone(ZoneOffset.UTC).toLocalDate().toString();
    }

    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    /** Descending unless the caller says otherwise: an operations table starts with what is new. */
    private static boolean ascending(String direction) {
        return "asc".equalsIgnoreCase(direction);
    }

    private static String correlationId() {
        return MDC.get(LogFields.CORRELATION_ID);
    }
}
