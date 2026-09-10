package com.distroq.reliability;

import com.distroq.config.DistroqProperties;
import com.distroq.effects.JobEffectService;
import com.distroq.model.Job;
import com.distroq.model.JobAttempt;
import com.distroq.model.JobEffect;
import com.distroq.model.OutboxEvent;
import com.distroq.model.OutboxStatus;
import com.distroq.model.ReliabilityActionType;
import com.distroq.observability.Events;
import com.distroq.observability.LogContext;
import com.distroq.outbox.OutboxEventType;
import com.distroq.outbox.OutboxPayload;
import com.distroq.outbox.OutboxService;
import com.distroq.queue.EnqueueSource;
import com.distroq.queue.ScheduledJobQueue;
import com.distroq.repository.JobAttemptRepository;
import com.distroq.repository.JobEffectRepository;
import com.distroq.repository.JobRepository;
import com.distroq.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Compares durable intent in PostgreSQL against what the rest of the system did about it.
 *
 * <p>The source of truth is the outbox table and the job table, never the Redis Streams. A stream
 * keeps acknowledged entries, so "the entry is in the stream" does not mean the work is
 * outstanding, and trimming or a short-lived deduplication marker means "no entry" does not mean
 * the work never published. Only the rows DistroQ wrote inside a transaction can be reasoned
 * about after the fact. Redis is consulted in exactly one place — the scheduled sorted set, where
 * a member that is due and still present is positive evidence that promotion stopped.
 *
 * <p>Safe to run repeatedly and on more than one instance. Every run holds a PostgreSQL advisory
 * lock for its transaction, so two instances cannot repair the same row at once, and every repair
 * is a conditional state transition that is a no-op the second time. Nothing in here is
 * destructive: the worst outcome of a run is a report.
 */
@Service
public class ReconciliationService {

    /** Arbitrary but fixed: the advisory-lock namespace shared by every DistroQ instance. */
    static final long ADVISORY_LOCK_KEY = 0x4469_7374_726F_7100L;

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final OutboxEventRepository outboxEvents;
    private final JobRepository jobs;
    private final JobAttemptRepository attempts;
    private final JobEffectRepository effects;
    private final JobEffectService effectService;
    private final OutboxService outboxService;
    private final ScheduledJobQueue scheduledJobQueue;
    private final ReliabilityAuditService audit;
    private final ObjectMapper objectMapper;
    private final EntityManager entityManager;
    private final DistroqProperties.Reconciliation properties;
    private final DistroqProperties.Effects effectProperties;

    public ReconciliationService(OutboxEventRepository outboxEvents,
                                 JobRepository jobs,
                                 JobAttemptRepository attempts,
                                 JobEffectRepository effects,
                                 JobEffectService effectService,
                                 OutboxService outboxService,
                                 ScheduledJobQueue scheduledJobQueue,
                                 ReliabilityAuditService audit,
                                 ObjectMapper objectMapper,
                                 EntityManager entityManager,
                                 DistroqProperties properties) {
        this.outboxEvents = outboxEvents;
        this.jobs = jobs;
        this.attempts = attempts;
        this.effects = effects;
        this.effectService = effectService;
        this.outboxService = outboxService;
        this.scheduledJobQueue = scheduledJobQueue;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.entityManager = entityManager;
        this.properties = properties.reconciliation();
        this.effectProperties = properties.effects();
    }

    /**
     * @param requestedAutoRepair what the caller asked for. Configuration is the upper bound: a
     *                            request can ask for less than {@code auto-repair} allows and
     *                            never for more, so enabling repairs stays a deployment decision.
     * @param waitForLock         true for an operator-initiated run, which should give a real
     *                            answer even if it has to queue behind another instance; false
     *                            for the scheduled sweep, which should simply skip a tick.
     */
    @Transactional
    public ReconciliationReport run(boolean requestedAutoRepair, String reason, String actor,
                                    boolean waitForLock) {
        Instant startedAt = Instant.now();
        boolean allowed = properties.autoRepair();
        boolean repairing = allowed && requestedAutoRepair;

        if (!acquireLock(waitForLock)) {
            log.debug("Another instance holds the reconciliation lock; skipping this run");
            return new ReconciliationReport(startedAt, Instant.now(), requestedAutoRepair, allowed,
                    false, true, 0, properties.batchSize(), List.of());
        }
        if (requestedAutoRepair && !allowed) {
            log.warn("Reconciliation was asked to repair but distroq.reconciliation.auto-repair is "
                    + "false; running in preview mode");
        }

        Context context = new Context(repairing, reason, actor, startedAt,
                PageRequest.of(0, Math.max(1, properties.batchSize())));

        reconcileOutbox(context);
        reconcileScheduledJobs(context);
        reconcileRetries(context);
        reconcileLeases(context);
        reconcileEffects(context);

        ReconciliationReport report = new ReconciliationReport(startedAt, Instant.now(),
                requestedAutoRepair, allowed, repairing, false, context.inspected,
                properties.batchSize(), List.copyOf(context.findings));
        if (report.findingCount() > 0) {
            report.findings().forEach(ReconciliationService::announce);
            log.info("Reconciliation inspected {} row(s) and found {} issue(s): {} repaired, {} "
                    + "skipped, {} failed", report.inspected(), report.findingCount(),
                    report.repaired(), report.skipped(), report.failed());
        }
        return report;
    }

    /**
     * One line per finding, so an alert can count them by type without parsing the report body.
     * {@code detail} is structural by construction — see {@link ReliabilityFinding} — so it is
     * safe to log; a payload or an error body never reaches it.
     */
    private static void announce(ReliabilityFinding finding) {
        boolean repaired = finding.resolution() == Resolution.REPAIRED;
        try (LogContext ignored = LogContext
                .event(repaired ? Events.RECONCILIATION_REPAIR : Events.RECONCILIATION_FINDING)
                .eventType(finding.type())
                .status(finding.resolution())) {
            log.info("Reconciliation {} {} {}: {}", finding.resolution(), finding.targetType(),
                    finding.targetId(), finding.detail());
        }
    }

    // ------------------------------------------------------------------------------- outbox

    private void reconcileOutbox(Context context) {
        for (OutboxEvent event : outboxEvents.expiredLocks(context.now, context.page)) {
            context.inspected++;
            ReliabilityFinding finding = ReliabilityFinding.reported(
                    FindingType.EXPIRED_OUTBOX_LOCK, "OutboxEvent", event.getId().toString(),
                    "relay lease expired at " + event.getLockedUntil() + " after attempt "
                            + event.getAttemptCount());
            context.add(repair(context, finding, () -> {
                String before = describe(event);
                event.releaseExpiredLock();
                audit.record(ReliabilityActionType.OUTBOX_UNLOCK, "OutboxEvent", event.getId(),
                        context.reason, context.actor, before, describe(event));
                return "returned to " + event.getStatus();
            }));
        }

        Instant staleBefore = context.now.minusMillis(properties.staleOutboxAfterMs());
        for (OutboxEvent event : outboxEvents.stalePending(staleBefore, context.page)) {
            context.inspected++;
            context.add(ReliabilityFinding.reported(FindingType.STALE_PENDING_OUTBOX,
                    "OutboxEvent", event.getId().toString(),
                    "PENDING since " + event.getCreatedAt() + " after " + event.getAttemptCount()
                            + " attempt(s); the relay still owns it"));
            checkPayload(context, event);
        }

        for (OutboxEvent event : outboxEvents.findByStatusOrderByTerminalFailedAtAsc(
                OutboxStatus.FAILED, context.page)) {
            context.inspected++;
            ReliabilityFinding finding = ReliabilityFinding.reported(
                    FindingType.TERMINAL_OUTBOX_FAILURE, "OutboxEvent", event.getId().toString(),
                    "FAILED at " + event.getTerminalFailedAt() + " after "
                            + event.getAttemptCount() + " attempt(s) and "
                            + event.getOperatorRetryCount() + " operator retry(ies)");
            // the one repair that stays off even when auto-repair is on, unless an operator has
            // said in configuration that re-arming terminal events is acceptable here
            if (context.repairing && properties.requeueFailedOutbox()) {
                context.add(attempt(finding, () -> {
                    String before = describe(event);
                    event.operatorRetry(context.reason, Instant.now());
                    audit.record(ReliabilityActionType.OUTBOX_RETRY, "OutboxEvent", event.getId(),
                            context.reason, context.actor, before, describe(event));
                    return "re-armed by distroq.reconciliation.requeue-failed-outbox";
                }));
            } else {
                context.add(context.repairing
                        ? finding.resolvedAs(Resolution.SKIPPED,
                                finding.detail() + "; awaiting an operator retry")
                        : finding);
            }
            checkPayload(context, event);
        }

        for (OutboxEvent event : outboxEvents.inconsistentPublished(context.page)) {
            context.inspected++;
            boolean provable = event.getPublishedAt() != null;
            ReliabilityFinding finding = ReliabilityFinding.reported(
                    FindingType.INCONSISTENT_PUBLISHED_OUTBOX, "OutboxEvent",
                    event.getId().toString(),
                    "status=" + event.getStatus() + " publishedAt=" + event.getPublishedAt());
            if (!provable) {
                context.add(context.repairing
                        ? finding.resolvedAs(Resolution.SKIPPED, finding.detail()
                                + "; PUBLISHED with no timestamp cannot be proved either way")
                        : finding);
                continue;
            }
            context.add(repair(context, finding, () -> {
                String before = describe(event);
                event.reconcilePublishedStatus();
                audit.record(ReliabilityActionType.OUTBOX_REPUBLISH, "OutboxEvent", event.getId(),
                        context.reason, context.actor, before, describe(event));
                return "status reconciled to PUBLISHED";
            }));
        }

        for (OutboxEvent event : outboxEvents.orphaned(context.page)) {
            context.inspected++;
            context.add(ReliabilityFinding.reported(FindingType.ORPHANED_OUTBOX_EVENT,
                    "OutboxEvent", event.getId().toString(),
                    "aggregate job " + event.getAggregateId() + " no longer exists"));
        }
    }

    /** Structural validation only. The payload itself never reaches the finding. */
    private void checkPayload(Context context, OutboxEvent event) {
        try {
            OutboxPayload payload = objectMapper.readValue(event.getPayload(), OutboxPayload.class);
            if (!event.getId().equals(payload.eventId())
                    || !java.util.Objects.equals(event.getAggregateId(), payload.jobId())) {
                context.add(ReliabilityFinding.reported(FindingType.MALFORMED_OUTBOX_PAYLOAD,
                        "OutboxEvent", event.getId().toString(),
                        "payload identity disagrees with the row"));
            }
        } catch (Exception e) {
            context.add(ReliabilityFinding.reported(FindingType.MALFORMED_OUTBOX_PAYLOAD,
                    "OutboxEvent", event.getId().toString(), "payload does not deserialize"));
        }
    }

    // ---------------------------------------------------------------------- scheduled jobs

    private void reconcileScheduledJobs(Context context) {
        Instant staleBefore = context.now.minusMillis(properties.staleScheduledAfterMs());
        for (Job job : jobs.staleScheduled(staleBefore, context.page)) {
            context.inspected++;
            context.add(ReliabilityFinding.reported(FindingType.STALE_SCHEDULED_JOB, "Job",
                    job.getId().toString(),
                    "SCHEDULED with a due time of " + job.getScheduledAt() + " already past"));

            List<OutboxEvent> events = outboxEvents.scheduleEventsFor(job.getId());
            if (events.isEmpty()) {
                ReliabilityFinding finding = ReliabilityFinding.reported(
                        FindingType.SCHEDULED_JOB_WITHOUT_EVENT, "Job", job.getId().toString(),
                        "no SCHEDULE_USER_JOB event exists, so no publication can exist");
                context.add(repair(context, finding, () -> {
                    OutboxEvent created = outboxService.create(job,
                            OutboxEventType.SCHEDULE_USER_JOB, EnqueueSource.SCHEDULED,
                            job.getScheduledAt());
                    audit.record(ReliabilityActionType.SCHEDULED_JOB_REPAIR, "Job", job.getId(),
                            context.reason, context.actor, "no schedule event",
                            "created outbox event " + created.getId() + " at "
                                    + job.getPriority());
                    return "schedule event " + created.getId() + " created";
                }));
                continue;
            }
            reportEventAnomalies(context, job, events, FindingType.SCHEDULED_JOB_EVENT_FAILED,
                    FindingType.DUPLICATE_SCHEDULE_EVENT, true);
        }
        reportUnpromotedMembers(context);
    }

    /**
     * Redis is asked exactly one question: is a member that is already due still sitting in the
     * scheduled set? A yes that survives the stale threshold means the promoter is not running,
     * which no PostgreSQL table can show. A Redis outage answers nothing and is logged rather
     * than turned into a finding, because "I could not look" is not evidence of a problem.
     */
    private void reportUnpromotedMembers(Context context) {
        Instant dueBefore = context.now.minusMillis(properties.staleScheduledAfterMs());
        try {
            for (UUID jobId : scheduledJobQueue.overdueJobIds(dueBefore, properties.batchSize())) {
                jobs.findById(jobId)
                        .filter(Job::isScheduled)
                        .ifPresent(job -> {
                            context.inspected++;
                            context.add(ReliabilityFinding.reported(
                                    FindingType.UNPROMOTED_SCHEDULED_MEMBER, "Job",
                                    jobId.toString(), "still on " + scheduledJobQueue.key()
                                            + " with a due time before " + dueBefore));
                        });
            }
        } catch (RuntimeException e) {
            log.warn("Could not read the scheduled sorted set during reconciliation; the "
                    + "PostgreSQL checks are unaffected", e);
        }
    }

    // ------------------------------------------------------------------------------ retries

    private void reconcileRetries(Context context) {
        Instant staleBefore = context.now.minusMillis(properties.staleScheduledAfterMs());
        for (Job job : jobs.staleRetrying(staleBefore, context.page)) {
            context.inspected++;
            context.add(ReliabilityFinding.reported(FindingType.STALE_RETRY_JOB, "Job",
                    job.getId().toString(),
                    "RETRYING with nextAttemptAt " + job.getNextAttemptAt() + " already past"));

            List<OutboxEvent> events = outboxEvents.retryEventsFor(job.getId());
            if (events.isEmpty()) {
                ReliabilityFinding finding = ReliabilityFinding.reported(
                        FindingType.RETRY_JOB_WITHOUT_EVENT, "Job", job.getId().toString(),
                        "no SCHEDULE_RETRY event exists, so no publication can exist");
                context.add(repair(context, finding, () -> {
                    OutboxEvent created = outboxService.create(job, OutboxEventType.SCHEDULE_RETRY,
                            EnqueueSource.RETRY, job.getNextAttemptAt());
                    audit.record(ReliabilityActionType.RETRY_JOB_REPAIR, "Job", job.getId(),
                            context.reason, context.actor, "no retry event",
                            "created outbox event " + created.getId() + " at " + job.getPriority());
                    return "retry event " + created.getId() + " created";
                }));
                continue;
            }
            reportEventAnomalies(context, job, events, FindingType.RETRY_EVENT_FAILED,
                    FindingType.DUPLICATE_RETRY_EVENT, false);
        }
    }

    /**
     * @param oneEventPerLifetime true when a job may only ever have one non-terminal event of this
     *                            type, as for a user schedule. False for retries, where one event
     *                            per attempt is normal and only the unpublished ones can compete:
     *                            a job on its third attempt legitimately has two published
     *                            SCHEDULE_RETRY events behind it.
     */
    private void reportEventAnomalies(Context context, Job job, List<OutboxEvent> events,
                                      FindingType failedType, FindingType duplicateType,
                                      boolean oneEventPerLifetime) {
        events.stream()
                .filter(OutboxEvent::isTerminal)
                .forEach(event -> context.add(ReliabilityFinding.reported(failedType, "OutboxEvent",
                        event.getId().toString(), "event for job " + job.getId()
                                + " is FAILED; the job cannot proceed until it is retried")));

        long active = events.stream()
                .filter(event -> event.getStatus() == OutboxStatus.PENDING
                        || event.getStatus() == OutboxStatus.PUBLISHING)
                .count();
        long published = events.stream()
                .filter(event -> event.getStatus() == OutboxStatus.PUBLISHED)
                .count();
        long competing = oneEventPerLifetime ? active + published : active;
        if (competing > 1) {
            context.add(ReliabilityFinding.reported(duplicateType, "Job", job.getId().toString(),
                    competing + " competing event(s) for one job: " + active + " unpublished, "
                            + published + " published"));
        }
    }

    // ---------------------------------------------------------------------- execution leases

    private void reconcileLeases(Context context) {
        Instant staleBefore = context.now.minusMillis(properties.staleLeaseAfterMs());
        for (Job job : jobs.expiredLeases(staleBefore, context.page)) {
            context.inspected++;
            ReliabilityFinding finding = ReliabilityFinding.reported(
                    FindingType.EXPIRED_EXECUTION_LEASE, "Job", job.getId().toString(),
                    "RUNNING with a lease that expired at " + job.getExecutionLeaseUntil()
                            + ", owner " + job.getExecutionOwner());
            // never repaired: recovery belongs to XAUTOCLAIM plus a fresh database claim, and a
            // second claim created here would compete with the worker that is about to make one
            context.add(context.repairing
                    ? finding.resolvedAs(Resolution.SKIPPED, finding.detail()
                            + "; recovery is owned by stream reclaim, not by reconciliation")
                    : finding);
            inspectAttempts(context, job);
        }

        for (Job job : jobs.terminalWithLease(context.page)) {
            context.inspected++;
            ReliabilityFinding finding = ReliabilityFinding.reported(
                    FindingType.TERMINAL_JOB_HOLDING_LEASE, "Job", job.getId().toString(),
                    job.getStatus() + " but still holding owner " + job.getExecutionOwner());
            context.add(repair(context, finding, () -> {
                String before = "owner=" + job.getExecutionOwner() + " attempt="
                        + job.getActiveAttemptId();
                job.releaseTerminalLease();
                audit.record(ReliabilityActionType.LEASE_REPAIR, "Job", job.getId(),
                        context.reason, context.actor, before, "lease cleared");
                return "lease bookkeeping cleared from a " + job.getStatus() + " job";
            }));
        }
    }

    private void inspectAttempts(Context context, Job job) {
        UUID activeAttemptId = job.getActiveAttemptId();
        if (activeAttemptId != null) {
            Optional<JobAttempt> attempt = attempts.findById(activeAttemptId);
            if (attempt.isEmpty()) {
                context.add(ReliabilityFinding.reported(FindingType.MISSING_ACTIVE_ATTEMPT, "Job",
                        job.getId().toString(),
                        "activeAttemptId " + activeAttemptId + " has no attempt row"));
            } else if (!java.util.Objects.equals(attempt.get().getWorkerId(),
                    job.getExecutionOwner())) {
                context.add(ReliabilityFinding.reported(FindingType.ATTEMPT_OWNER_MISMATCH, "Job",
                        job.getId().toString(), "lease owner " + job.getExecutionOwner()
                                + " but the active attempt belongs to "
                                + attempt.get().getWorkerId()));
            }
        }
        if (attempts.countInProgress(job.getId()) > 1) {
            context.add(ReliabilityFinding.reported(FindingType.MULTIPLE_IN_PROGRESS_ATTEMPTS,
                    "Job", job.getId().toString(),
                    attempts.countInProgress(job.getId()) + " attempts are IN_PROGRESS at once"));
        }
    }

    // ------------------------------------------------------------------------------ effects

    private void reconcileEffects(Context context) {
        if (!effectProperties.enabled()) {
            return;
        }
        for (JobEffect effect : effects.staleStarted(effectService.staleBefore(context.now),
                context.page)) {
            context.inspected++;
            ReliabilityFinding finding = ReliabilityFinding.reported(
                    FindingType.STALE_STARTED_EFFECT, "JobEffect", effect.getEffectKey(),
                    "STARTED since " + effect.getCreatedAt() + " for job " + effect.getJobId()
                            + "; whether the external effect happened is unknown");
            if (!context.repairing || !effectProperties.autoFailStale()) {
                context.add(context.repairing
                        ? finding.resolvedAs(Resolution.SKIPPED, finding.detail()
                                + "; distroq.effects.auto-fail-stale is false")
                        : finding);
                continue;
            }
            // the policy closes the row as FAILED, never as COMPLETED: marking it completed would
            // assert an effect happened that nobody observed, and would suppress the retry that
            // is the only remaining way to make it happen
            context.add(attempt(finding, () -> {
                effect.fail("Closed by reconciliation after exceeding "
                        + effectProperties.staleStartedAfterMs()
                        + "ms in STARTED; the effect may already have occurred", Instant.now());
                audit.record(ReliabilityActionType.STALE_EFFECT_REPAIR, "JobEffect",
                        effect.getJobId(), context.reason, context.actor, "STARTED",
                        "FAILED and available for a new claim");
                return "closed as FAILED under the stale-effect policy";
            }));
        }
    }

    // ------------------------------------------------------------------------------ plumbing

    private ReliabilityFinding repair(Context context, ReliabilityFinding finding,
                                      Repair action) {
        if (!context.repairing) {
            return finding;
        }
        if (!finding.type().autoRepairable()) {
            return finding.resolvedAs(Resolution.SKIPPED,
                    finding.detail() + "; this finding type is never repaired automatically");
        }
        return attempt(finding, action);
    }

    private ReliabilityFinding attempt(ReliabilityFinding finding, Repair action) {
        try {
            return finding.resolvedAs(Resolution.REPAIRED, action.perform());
        } catch (RuntimeException e) {
            log.error("Repair of {} {} failed", finding.type(), finding.targetId(), e);
            return finding.resolvedAs(Resolution.FAILED,
                    finding.detail() + "; repair failed: " + e.getClass().getSimpleName());
        }
    }

    private boolean acquireLock(boolean waitForLock) {
        if (waitForLock) {
            entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(?1)")
                    .setParameter(1, ADVISORY_LOCK_KEY)
                    .getSingleResult();
            return true;
        }
        return Boolean.TRUE.equals(entityManager
                .createNativeQuery("SELECT pg_try_advisory_xact_lock(?1)")
                .setParameter(1, ADVISORY_LOCK_KEY)
                .getSingleResult());
    }

    private static String describe(OutboxEvent event) {
        return "status=" + event.getStatus() + " attemptCount=" + event.getAttemptCount()
                + " lockedUntil=" + event.getLockedUntil()
                + " publishedAt=" + event.getPublishedAt();
    }

    @FunctionalInterface
    private interface Repair {
        String perform();
    }

    private static final class Context {
        private final boolean repairing;
        private final String reason;
        private final String actor;
        private final Instant now;
        private final Pageable page;
        private final List<ReliabilityFinding> findings = new ArrayList<>();
        private int inspected;

        private Context(boolean repairing, String reason, String actor, Instant now, Pageable page) {
            this.repairing = repairing;
            this.reason = reason;
            this.actor = actor;
            this.now = now;
            this.page = page;
        }

        private void add(ReliabilityFinding finding) {
            findings.add(finding);
        }
    }
}
