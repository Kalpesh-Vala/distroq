package com.distroq.reliability;

import com.distroq.TestProperties;
import com.distroq.config.DistroqProperties;
import com.distroq.effects.JobEffectService;
import com.distroq.effects.JobEffects;
import com.distroq.model.Job;
import com.distroq.model.JobAttempt;
import com.distroq.model.OutboxEvent;
import com.distroq.model.OutboxStatus;
import com.distroq.model.Priority;
import com.distroq.model.ReliabilityActionType;
import com.distroq.outbox.OutboxEventType;
import com.distroq.outbox.OutboxService;
import com.distroq.queue.ScheduledJobQueue;
import com.distroq.repository.JobAttemptRepository;
import com.distroq.repository.JobEffectRepository;
import com.distroq.repository.JobRepository;
import com.distroq.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReconciliationServiceTest {

    private final OutboxEventRepository outboxEvents = mock(OutboxEventRepository.class);
    private final JobRepository jobs = mock(JobRepository.class);
    private final JobAttemptRepository attempts = mock(JobAttemptRepository.class);
    private final JobEffectRepository effects = mock(JobEffectRepository.class);
    private final JobEffectService effectService = mock(JobEffectService.class);
    private final OutboxService outboxService = mock(OutboxService.class);
    private final ScheduledJobQueue scheduledJobQueue = mock(ScheduledJobQueue.class);
    private final ReliabilityAuditService audit = mock(ReliabilityAuditService.class);
    private final EntityManager entityManager = mock(EntityManager.class);

    @BeforeEach
    void setUp() {
        Query lockQuery = mock(Query.class);
        when(entityManager.createNativeQuery(anyString())).thenReturn(lockQuery);
        when(lockQuery.setParameter(anyInt(), any())).thenReturn(lockQuery);
        when(lockQuery.getSingleResult()).thenReturn(Boolean.TRUE);
        when(effectService.staleBefore(any(Instant.class))).thenReturn(Instant.EPOCH);
    }

    @Test
    void anExpiredRelayLockIsDetected() {
        OutboxEvent event = expiredLock();
        when(outboxEvents.expiredLocks(any(), any())).thenReturn(List.of(event));

        ReconciliationReport report = preview();

        assertThat(types(report)).contains(FindingType.EXPIRED_OUTBOX_LOCK);
        assertThat(report.inspected()).isEqualTo(1);
    }

    @Test
    void aStalePendingEventIsDetected() {
        when(outboxEvents.stalePending(any(), any())).thenReturn(List.of(event()));

        assertThat(types(preview())).contains(FindingType.STALE_PENDING_OUTBOX);
    }

    @Test
    void aPayloadWhoseIdentityDisagreesWithItsRowIsDetected() {
        UUID id = UUID.randomUUID();
        OutboxEvent mismatched = OutboxEvent.create(id, UUID.randomUUID(),
                OutboxEventType.ENQUEUE_SUBMIT,
                "{\"eventId\":\"" + id + "\",\"jobId\":\"" + UUID.randomUUID() + "\"}",
                Instant.now());
        when(outboxEvents.stalePending(any(), any())).thenReturn(List.of(mismatched));

        assertThat(types(preview())).contains(FindingType.MALFORMED_OUTBOX_PAYLOAD);
    }

    @Test
    void aPayloadThatDoesNotDeserializeIsDetected() {
        OutboxEvent broken = OutboxEvent.create(UUID.randomUUID(), UUID.randomUUID(),
                OutboxEventType.ENQUEUE_SUBMIT, "not json", Instant.now());
        when(outboxEvents.stalePending(any(), any())).thenReturn(List.of(broken));

        assertThat(types(preview())).contains(FindingType.MALFORMED_OUTBOX_PAYLOAD);
    }

    @Test
    void aTerminalEventIsDetectedAndNeverRepairedByDefault() {
        OutboxEvent terminal = terminal();
        when(outboxEvents.findByStatusOrderByTerminalFailedAtAsc(eq(OutboxStatus.FAILED), any()))
                .thenReturn(List.of(terminal));

        ReconciliationReport repaired = run(repairing(), true);

        assertThat(finding(repaired, FindingType.TERMINAL_OUTBOX_FAILURE).resolution())
                .isEqualTo(Resolution.SKIPPED);
        assertThat(terminal.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(terminal.getOperatorRetryCount()).isZero();
    }

    @Test
    void aStaleScheduledJobWithNoEventIsDetected() {
        Job job = scheduledJob();
        when(jobs.staleScheduled(any(), any())).thenReturn(List.of(job));
        when(outboxEvents.scheduleEventsFor(job.getId())).thenReturn(List.of());

        assertThat(types(preview()))
                .contains(FindingType.STALE_SCHEDULED_JOB, FindingType.SCHEDULED_JOB_WITHOUT_EVENT);
    }

    @Test
    void aScheduledJobWhoseEventIsTerminalIsReportedSeparatelyFromOneWithNoEvent() {
        Job job = scheduledJob();
        when(jobs.staleScheduled(any(), any())).thenReturn(List.of(job));
        when(outboxEvents.scheduleEventsFor(job.getId())).thenReturn(List.of(terminal()));

        assertThat(types(preview()))
                .contains(FindingType.SCHEDULED_JOB_EVENT_FAILED)
                .doesNotContain(FindingType.SCHEDULED_JOB_WITHOUT_EVENT);
    }

    @Test
    void aStaleRetryJobWithNoEventIsDetected() {
        Job job = retryingJob();
        when(jobs.staleRetrying(any(), any())).thenReturn(List.of(job));
        when(outboxEvents.retryEventsFor(job.getId())).thenReturn(List.of());

        assertThat(types(preview()))
                .contains(FindingType.STALE_RETRY_JOB, FindingType.RETRY_JOB_WITHOUT_EVENT);
    }

    @Test
    void anExpiredExecutionLeaseIsDetected() {
        when(jobs.expiredLeases(any(), any())).thenReturn(List.of(runningJob()));

        assertThat(types(preview())).contains(FindingType.EXPIRED_EXECUTION_LEASE);
    }

    @Test
    void aStaleStartedEffectIsDetected() {
        when(effects.staleStarted(any(), any())).thenReturn(List.of(staleEffect()));

        assertThat(types(preview())).contains(FindingType.STALE_STARTED_EFFECT);
    }

    // ------------------------------------------------- bookkeeping that only a direct write makes

    /**
     * Published, then failed. Not reachable through the relay, which writes the two in the other
     * order and never revisits a published row \u2014 so a row in this shape means something wrote the
     * table directly.
     */
    @Test
    void aRowThatPublishedButWhoseStatusNeverCaughtUpIsDetected() {
        when(outboxEvents.inconsistentPublished(any())).thenReturn(List.of(publishedThenFailed()));

        assertThat(types(preview())).contains(FindingType.INCONSISTENT_PUBLISHED_OUTBOX);
    }

    @Test
    void aPublishedTimestampIsProofEnoughToReconcileTheStatus() {
        OutboxEvent event = publishedThenFailed();
        when(outboxEvents.inconsistentPublished(any())).thenReturn(List.of(event));

        ReconciliationReport report = run(repairing(), true);

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(finding(report, FindingType.INCONSISTENT_PUBLISHED_OUTBOX).resolution())
                .isEqualTo(Resolution.REPAIRED);
        verify(audit).record(eq(ReliabilityActionType.OUTBOX_REPUBLISH), eq("OutboxEvent"),
                eq(event.getId()), anyString(), anyString(), anyString(), anyString());
    }

    /** The mirror case proves nothing in either direction, so it is reported and left alone. */
    @Test
    void aPublishedStatusWithNoTimestampIsSkippedRatherThanGuessedAt() {
        OutboxEvent event = event();
        event.markPublished(null);
        when(outboxEvents.inconsistentPublished(any())).thenReturn(List.of(event));

        ReconciliationReport report = run(repairing(), true);

        assertThat(finding(report, FindingType.INCONSISTENT_PUBLISHED_OUTBOX).resolution())
                .isEqualTo(Resolution.SKIPPED);
        verifyNoInteractions(audit);
    }

    @Test
    void anEventWhoseAggregateJobIsGoneIsDetectedAndNeverDeleted() {
        OutboxEvent orphan = event();
        when(outboxEvents.orphaned(any())).thenReturn(List.of(orphan));

        ReconciliationReport report = run(repairing(), true);

        assertThat(finding(report, FindingType.ORPHANED_OUTBOX_EVENT).resolution())
                .isEqualTo(Resolution.REPORTED);
        verifyNoInteractions(audit);
    }

    // ------------------------------------------------------------------------ competing events

    @Test
    void twoLiveScheduleEventsForOneJobAreDetected() {
        Job job = scheduledJob();
        when(jobs.staleScheduled(any(), any())).thenReturn(List.of(job));
        when(outboxEvents.scheduleEventsFor(job.getId())).thenReturn(List.of(event(), event()));

        assertThat(types(preview())).contains(FindingType.DUPLICATE_SCHEDULE_EVENT);
    }

    /** A user schedule publishes once, ever, so a published one plus a live one is a duplicate. */
    @Test
    void aPublishedScheduleEventBesideALiveOneIsADuplicate() {
        Job job = scheduledJob();
        when(jobs.staleScheduled(any(), any())).thenReturn(List.of(job));
        when(outboxEvents.scheduleEventsFor(job.getId())).thenReturn(List.of(published(), event()));

        assertThat(types(preview())).contains(FindingType.DUPLICATE_SCHEDULE_EVENT);
    }

    @Test
    void oneScheduleEventIsNotADuplicate() {
        Job job = scheduledJob();
        when(jobs.staleScheduled(any(), any())).thenReturn(List.of(job));
        when(outboxEvents.scheduleEventsFor(job.getId())).thenReturn(List.of(published()));

        assertThat(types(preview())).doesNotContain(FindingType.DUPLICATE_SCHEDULE_EVENT);
    }

    @Test
    void twoUnpublishedRetryEventsForOneJobAreDetected() {
        Job job = retryingJob();
        when(jobs.staleRetrying(any(), any())).thenReturn(List.of(job));
        when(outboxEvents.retryEventsFor(job.getId())).thenReturn(List.of(event(), event()));

        assertThat(types(preview())).contains(FindingType.DUPLICATE_RETRY_EVENT);
    }

    /**
     * The regression that matters: a job on its third attempt legitimately has two published retry
     * events behind it and one live one. Counting published events as competitors here would make
     * every healthy retrying job a finding.
     */
    @Test
    void aRetryHistoryOfPublishedEventsIsNotADuplicate() {
        Job job = retryingJob();
        when(jobs.staleRetrying(any(), any())).thenReturn(List.of(job));
        when(outboxEvents.retryEventsFor(job.getId()))
                .thenReturn(List.of(published(), published(), event()));

        assertThat(types(preview())).doesNotContain(FindingType.DUPLICATE_RETRY_EVENT);
    }

    // ------------------------------------------------------------- the one Redis-derived finding

    @Test
    void aDueMemberStillSittingInTheScheduledSetIsDetected() {
        Job job = scheduledJob();
        when(scheduledJobQueue.overdueJobIds(any(), anyInt())).thenReturn(Set.of(job.getId()));
        when(jobs.findById(job.getId())).thenReturn(Optional.of(job));

        assertThat(types(preview())).contains(FindingType.UNPROMOTED_SCHEDULED_MEMBER);
    }

    /** A member for a job that has since moved on is stale data, not a stuck promoter. */
    @Test
    void anOverdueMemberForAJobThatIsNoLongerScheduledIsNotAFinding() {
        Job job = runningJob();
        when(scheduledJobQueue.overdueJobIds(any(), anyInt())).thenReturn(Set.of(job.getId()));
        when(jobs.findById(job.getId())).thenReturn(Optional.of(job));

        assertThat(types(preview())).doesNotContain(FindingType.UNPROMOTED_SCHEDULED_MEMBER);
    }

    /** "I could not look" is not evidence of a problem, and must not stop the PostgreSQL checks. */
    @Test
    void aRedisOutageProducesNoFindingAndDoesNotAbortTheRun() {
        when(scheduledJobQueue.overdueJobIds(any(), anyInt()))
                .thenThrow(new IllegalStateException("Redis command timed out"));
        when(outboxEvents.stalePending(any(), any())).thenReturn(List.of(event()));

        ReconciliationReport report = preview();

        assertThat(types(report))
                .contains(FindingType.STALE_PENDING_OUTBOX)
                .doesNotContain(FindingType.UNPROMOTED_SCHEDULED_MEMBER);
    }

    // ------------------------------------------------------------------------ attempt anomalies

    @Test
    void anActiveAttemptIdPointingAtNoAttemptRowIsDetected() {
        UUID attemptId = UUID.randomUUID();
        Job job = runningJob("worker-one", attemptId);
        when(jobs.expiredLeases(any(), any())).thenReturn(List.of(job));
        when(attempts.findById(attemptId)).thenReturn(Optional.empty());

        assertThat(types(preview())).contains(FindingType.MISSING_ACTIVE_ATTEMPT);
    }

    @Test
    void twoAttemptsInProgressForOneJobAreDetected() {
        Job job = runningJob();
        when(jobs.expiredLeases(any(), any())).thenReturn(List.of(job));
        when(attempts.countInProgress(job.getId())).thenReturn(2L);

        assertThat(types(preview())).contains(FindingType.MULTIPLE_IN_PROGRESS_ATTEMPTS);
    }

    @Test
    void aLeaseOwnerThatDisagreesWithItsActiveAttemptIsDetected() {
        UUID attemptId = UUID.randomUUID();
        Job job = runningJob("worker-one", attemptId);
        when(jobs.expiredLeases(any(), any())).thenReturn(List.of(job));
        when(attempts.findById(attemptId)).thenReturn(Optional.of(
                JobAttempt.started(attemptId, job.getId(), "worker-two", 1, Instant.now())));

        assertThat(types(preview())).contains(FindingType.ATTEMPT_OWNER_MISMATCH);
    }

    @Test
    void anAgreeingOwnerAndAttemptProduceNoAnomaly() {
        UUID attemptId = UUID.randomUUID();
        Job job = runningJob("worker-one", attemptId);
        when(jobs.expiredLeases(any(), any())).thenReturn(List.of(job));
        when(attempts.findById(attemptId)).thenReturn(Optional.of(
                JobAttempt.started(attemptId, job.getId(), "worker-one", 1, Instant.now())));
        when(attempts.countInProgress(job.getId())).thenReturn(1L);

        assertThat(types(preview()))
                .containsExactly(FindingType.EXPIRED_EXECUTION_LEASE);
    }

    @Test
    void findingsAreGroupedIntoEveryCategoryEvenWhenACategoryIsEmpty() {
        when(outboxEvents.stalePending(any(), any())).thenReturn(List.of(event()));

        ReconciliationReport report = preview();

        assertThat(report.byCategory()).containsOnlyKeys(FindingCategory.values());
        assertThat(report.byCategory().get(FindingCategory.OUTBOX)).hasSize(1);
        assertThat(report.byCategory().get(FindingCategory.EFFECTS)).isEmpty();
    }

    @Test
    void previewChangesNothingAndAuditsNothing() {
        OutboxEvent event = expiredLock();
        when(outboxEvents.expiredLocks(any(), any())).thenReturn(List.of(event));

        ReconciliationReport report = preview();

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHING);
        assertThat(report.repaired()).isZero();
        assertThat(report.autoRepairApplied()).isFalse();
        verifyNoInteractions(audit);
    }

    @Test
    void configurationIsTheUpperBoundOnRepairsAndARequestCannotRaiseIt() {
        OutboxEvent event = expiredLock();
        when(outboxEvents.expiredLocks(any(), any())).thenReturn(List.of(event));

        ReconciliationReport report = run(service(TestProperties.defaults()), true);

        assertThat(report.autoRepairRequested()).isTrue();
        assertThat(report.autoRepairAllowedByConfiguration()).isFalse();
        assertThat(report.autoRepairApplied()).isFalse();
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHING);
        verifyNoInteractions(audit);
    }

    @Test
    void aRequestMayAskForLessThanConfigurationAllows() {
        OutboxEvent event = expiredLock();
        when(outboxEvents.expiredLocks(any(), any())).thenReturn(List.of(event));

        ReconciliationReport report = run(repairing(), false);

        assertThat(report.autoRepairApplied()).isFalse();
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHING);
    }

    @Test
    void anAllowedRepairIsPerformedAndAudited() {
        OutboxEvent event = expiredLock();
        when(outboxEvents.expiredLocks(any(), any())).thenReturn(List.of(event));

        ReconciliationReport report = run(repairing(), true);

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getLockedUntil()).isNull();
        assertThat(report.repaired()).isEqualTo(1);
        verify(audit).record(eq(ReliabilityActionType.OUTBOX_UNLOCK), eq("OutboxEvent"),
                eq(event.getId()), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void aScheduledJobWithNoEventIsRepairedByCreatingOneRatherThanTouchingRedis() {
        Job job = scheduledJob();
        when(jobs.staleScheduled(any(), any())).thenReturn(List.of(job));
        when(outboxEvents.scheduleEventsFor(job.getId())).thenReturn(List.of());
        when(outboxService.create(any(), any(), any(), any())).thenReturn(event());

        run(repairing(), true);

        verify(outboxService).create(eq(job), eq(OutboxEventType.SCHEDULE_USER_JOB), any(), any());
        verify(audit).record(eq(ReliabilityActionType.SCHEDULED_JOB_REPAIR), eq("Job"),
                eq(job.getId()), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void anExpiredLeaseIsNeverRepairedEvenWhenRepairsAreAllowed() {
        Job job = runningJob();
        when(jobs.expiredLeases(any(), any())).thenReturn(List.of(job));

        ReconciliationReport report = run(repairing(), true);

        assertThat(finding(report, FindingType.EXPIRED_EXECUTION_LEASE).resolution())
                .isEqualTo(Resolution.SKIPPED);
        verify(audit, never()).record(eq(ReliabilityActionType.LEASE_REPAIR), anyString(), any(),
                anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void aStaleEffectIsNeverSilentlyMarkedCompleted() {
        when(effects.staleStarted(any(), any())).thenReturn(List.of(staleEffect()));

        ReconciliationReport report = run(repairing(), true);

        assertThat(finding(report, FindingType.STALE_STARTED_EFFECT).resolution())
                .isEqualTo(Resolution.SKIPPED);
    }

    @Test
    void runningTwiceIsSafeBecauseTheSecondRunFindsNothingLeftToRepair() {
        OutboxEvent event = expiredLock();
        when(outboxEvents.expiredLocks(any(), any()))
                .thenReturn(List.of(event))
                .thenReturn(List.of());
        ReconciliationService service = repairing();

        ReconciliationReport first = run(service, true);
        ReconciliationReport second = run(service, true);

        assertThat(first.repaired()).isEqualTo(1);
        assertThat(second.findingCount()).isZero();
        assertThat(second.repaired()).isZero();
    }

    @Test
    void aRunThatCannotTakeTheAdvisoryLockReportsThatRatherThanScanning() {
        Query lockQuery = mock(Query.class);
        when(entityManager.createNativeQuery(anyString())).thenReturn(lockQuery);
        when(lockQuery.setParameter(anyInt(), any())).thenReturn(lockQuery);
        when(lockQuery.getSingleResult()).thenReturn(Boolean.FALSE);

        ReconciliationReport report =
                service(TestProperties.defaults()).run(false, "sweep", "reconciliation", false);

        assertThat(report.skippedBecauseAnotherRunHoldsTheLock()).isTrue();
        assertThat(report.findingCount()).isZero();
        verifyNoInteractions(outboxEvents);
    }

    // ------------------------------------------------------------------------------- helpers

    private ReconciliationReport preview() {
        return run(service(TestProperties.defaults()), false);
    }

    private ReconciliationReport run(ReconciliationService service, boolean requestedAutoRepair) {
        return service.run(requestedAutoRepair, "reconciliation test", "operator", true);
    }

    private ReconciliationService repairing() {
        return service(TestProperties.of(new DistroqProperties.Reconciliation(true, 30_000L, 100,
                60_000L, 60_000L, 60_000L, true, false)));
    }

    private ReconciliationService service(DistroqProperties properties) {
        return new ReconciliationService(outboxEvents, jobs, attempts, effects, effectService,
                outboxService, scheduledJobQueue, audit, new ObjectMapper(), entityManager,
                properties);
    }

    private static List<FindingType> types(ReconciliationReport report) {
        return report.findings().stream().map(ReliabilityFinding::type).toList();
    }

    private static ReliabilityFinding finding(ReconciliationReport report, FindingType type) {
        return report.findings().stream()
                .filter(candidate -> candidate.type() == type)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No " + type + " finding in " + report));
    }

    private static OutboxEvent event() {
        UUID id = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        return OutboxEvent.create(id, jobId, OutboxEventType.ENQUEUE_SUBMIT,
                "{\"eventId\":\"" + id + "\",\"jobId\":\"" + jobId + "\",\"priority\":\"NORMAL\","
                        + "\"source\":\"SUBMIT\"}", Instant.now());
    }

    private static OutboxEvent expiredLock() {
        OutboxEvent event = event();
        event.claimUntil(Instant.now().minusSeconds(60));
        return event;
    }

    private static OutboxEvent published() {
        OutboxEvent event = event();
        event.markPublished(Instant.now().minusSeconds(60));
        return event;
    }

    /** Published, then failed: {@code published_at} survives but the status no longer agrees. */
    private static OutboxEvent publishedThenFailed() {
        OutboxEvent event = published();
        event.markFailed("written out of band", 100);
        return event;
    }

    private static OutboxEvent terminal() {
        OutboxEvent event = event();
        event.markFailed("redis unavailable", 1);
        return event;
    }

    private static Job scheduledJob() {
        return Job.create("sleep", "10", 3, Priority.NORMAL, Instant.now().plusSeconds(600));
    }

    private static Job retryingJob() {
        Job job = Job.create("always_fail", "", 3);
        job.markRunning();
        job.markRetrying("boom", Instant.now().minusSeconds(600));
        return job;
    }

    private static Job runningJob() {
        Job job = Job.create("sleep", "10", 3);
        job.markRunning();
        return job;
    }

    /**
     * A RUNNING job holding lease bookkeeping. Reflection, because production writes those three
     * columns only through the conditional UPDATE in {@code JobRepository.claimExecution} — adding
     * setters so a test could reach them would put a second, unguarded way into the same state.
     */
    private static Job runningJob(String owner, UUID attemptId) {
        Job job = runningJob();
        set(job, "executionOwner", owner);
        set(job, "activeAttemptId", attemptId);
        set(job, "executionLeaseUntil", Instant.now().minusSeconds(600));
        return job;
    }

    private static void set(Job job, String field, Object value) {
        try {
            Field declared = Job.class.getDeclaredField(field);
            declared.setAccessible(true);
            declared.set(job, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not seed Job." + field + " for a test", e);
        }
    }

    private static com.distroq.model.JobEffect staleEffect() {
        return JobEffects.started(UUID.randomUUID() + ":counter:orders:daily", UUID.randomUUID(),
                1, "counter", Instant.EPOCH);
    }
}
