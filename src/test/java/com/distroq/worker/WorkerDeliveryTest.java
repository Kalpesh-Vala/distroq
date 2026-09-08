package com.distroq.worker;

import com.distroq.TestProperties;
import com.distroq.model.AttemptOutcome;
import com.distroq.model.Job;
import com.distroq.model.JobAttempt;
import com.distroq.model.JobStatus;
import com.distroq.model.Priority;
import com.distroq.queue.EnqueueSource;
import com.distroq.queue.JobQueue;
import com.distroq.queue.JobStreamConsumer;
import com.distroq.queue.JobStreamEntry;
import com.distroq.queue.StreamDelivery;
import com.distroq.repository.JobAttemptRepository;
import com.distroq.repository.JobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * What a delivery <em>means</em>, which is the decision at-least-once delivery forced on v0.5.
 *
 * <p>No Redis and no database: the rules are about job state and entry provenance, and both are
 * arguments. The one thing every branch must do is acknowledge — an entry that is neither executed
 * nor acknowledged sits in the Pending Entries List until the idle timeout and comes straight back.
 */
class WorkerDeliveryTest {

    private static final String STREAM = "distroq:jobs:stream:normal";
    private static final String ENTRY = "1788715215167-0";
    private static final String CONSUMER = "worker-bbbbbbbb";

    private JobQueue jobQueue;
    private JobStreamConsumer consumer;
    private JobRepository jobRepository;
    private JobAttemptRepository jobAttemptRepository;
    private JobExecutor jobExecutor;
    private DeadLetterWriter deadLetterWriter;
    private ExecutionClaimService claims;
    private Worker worker;

    @BeforeEach
    void setUp() {
        jobQueue = mock(JobQueue.class);
        consumer = mock(JobStreamConsumer.class);
        jobRepository = mock(JobRepository.class);
        jobAttemptRepository = mock(JobAttemptRepository.class);
        jobExecutor = mock(JobExecutor.class);
        deadLetterWriter = mock(DeadLetterWriter.class);
        claims = mock(ExecutionClaimService.class);

        when(consumer.consumerName()).thenReturn(CONSUMER);
        when(jobRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(jobAttemptRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(jobAttemptRepository.findByJobIdAndOutcomeOrderByAttemptNumberAsc(any(), any()))
                .thenReturn(List.of());
        when(claims.claim(any(), any(), any())).thenAnswer(invocation -> {
            UUID jobId = invocation.getArgument(0);
            String owner = invocation.getArgument(1);
            Job job = jobRepository.findById(jobId).orElseThrow();
            for (JobAttempt open : jobAttemptRepository
                    .findByJobIdAndOutcomeOrderByAttemptNumberAsc(jobId, AttemptOutcome.IN_PROGRESS)) {
                open.abandon(Instant.now(), "claimed by " + owner + "; "
                    + open.getWorkerId() + " no longer owns the database lease");
                jobAttemptRepository.save(open);
            }
            job.markRunning();
            JobAttempt attempt = jobAttemptRepository.save(
                    JobAttempt.started(jobId, owner, job.getAttemptCount(), job.getStartedAt()));
            return Optional.of(new ExecutionClaimService.Claim(job, attempt));
        });
        when(claims.succeed(any())).thenAnswer(invocation -> {
            ExecutionClaimService.Claim claim = invocation.getArgument(0);
            claim.job().markSucceeded();
            claim.attempt().succeed(Instant.now());
            jobAttemptRepository.save(claim.attempt());
            return true;
        });
        when(claims.retry(any(), any(), any())).thenAnswer(invocation -> {
            ExecutionClaimService.Claim claim = invocation.getArgument(0);
            String error = invocation.getArgument(1);
            Instant dueAt = invocation.getArgument(2);
            claim.job().markRetrying(error, dueAt);
            claim.attempt().fail(Instant.now(), error);
            jobAttemptRepository.save(claim.attempt());
            return true;
        });
        when(claims.deadLetter(any(), any())).thenAnswer(invocation -> {
            ExecutionClaimService.Claim claim = invocation.getArgument(0);
            String error = invocation.getArgument(1);
            claim.job().markDeadLettered(error);
            claim.attempt().fail(Instant.now(), error);
            jobAttemptRepository.save(claim.attempt());
            deadLetterWriter.deadLetter(claim.job(), error);
            return true;
        });

        worker = new Worker(consumer, jobRepository, jobExecutor,
                new BackoffPolicy(TestProperties.defaults()), new PriorityStrategy(TestProperties.defaults()),
                claims, new WorkerMetrics(TestProperties.defaults()), TestProperties.defaults());
    }

    @Test
    void aQueuedJobIsExecutedAndAcknowledgedAfterwards() throws Exception {
        Job job = queued(Priority.NORMAL);

        worker.handle(delivery(job.getId(), Priority.NORMAL, EnqueueSource.SUBMIT));

        verify(jobExecutor).execute(job);
        assertThat(job.getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        verify(consumer).acknowledge(STREAM, ENTRY);
    }

    @Test
    void anAlreadySucceededJobIsAcknowledgedWithoutExecuting() throws Exception {
        Job job = queued(Priority.NORMAL);
        job.markRunning();
        job.markSucceeded();
        present(job);

        worker.handle(delivery(job.getId(), Priority.NORMAL, EnqueueSource.SUBMIT));

        verifyNoInteractions(jobExecutor);
        verify(consumer).acknowledge(STREAM, ENTRY);
        verify(jobRepository, never()).save(any());
    }

    @Test
    void anAlreadyDeadLetteredJobIsAcknowledgedWithoutExecuting() throws Exception {
        Job job = queued(Priority.NORMAL);
        job.markRunning();
        job.markDeadLettered("out of attempts");
        present(job);

        worker.handle(delivery(job.getId(), Priority.NORMAL, EnqueueSource.SUBMIT));

        verifyNoInteractions(jobExecutor);
        verify(consumer).acknowledge(STREAM, ENTRY);
    }

    @Test
    void theOriginalDeliveryOfARetryingJobIsAcknowledgedWithoutExecuting() throws Exception {
        // the worker that scheduled the retry died before XACK, so recovery hands its entry back.
        // Running it now would execute ahead of the backoff and still leave the real retry entry
        Job job = retrying(Instant.now().plusSeconds(30));

        worker.handle(delivery(job.getId(), Priority.NORMAL, EnqueueSource.SUBMIT));

        verifyNoInteractions(jobExecutor);
        verify(consumer).acknowledge(STREAM, ENTRY);
        assertThat(job.getStatus()).isEqualTo(JobStatus.RETRYING);
    }

    @Test
    void theScheduledRetryOfARetryingJobIsExecuted() throws Exception {
        // this is the normal retry path: the promotion script only writes this entry once the
        // due time has passed, so it is the next attempt rather than a stale redelivery
        Instant dueAt = Instant.now().minusSeconds(1);
        Job job = retrying(dueAt);

        worker.handle(new StreamDelivery(STREAM, ENTRY, job.getId(), Priority.NORMAL,
                fields(job.getId(), Priority.NORMAL, EnqueueSource.RETRY, dueAt.toEpochMilli())));

        verify(jobExecutor).execute(job);
        assertThat(job.getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        verify(consumer).acknowledge(STREAM, ENTRY);
    }

    @Test
    void aRetryEntryOlderThanTheCurrentScheduleIsAcknowledgedWithoutExecuting() throws Exception {
        Instant dueAt = Instant.now().plusSeconds(30);
        Job job = retrying(dueAt);

        worker.handle(new StreamDelivery(STREAM, ENTRY, job.getId(), Priority.NORMAL,
                fields(job.getId(), Priority.NORMAL, EnqueueSource.RETRY,
                        dueAt.toEpochMilli() - 10_000)));

        verifyNoInteractions(jobExecutor);
        verify(consumer).acknowledge(STREAM, ENTRY);
    }

    @Test
    void aRedeliveredRunningJobIsReclaimedAndRunAgainAsTheNextAttempt() throws Exception {
        Job job = queued(Priority.NORMAL);
        job.markRunning();
        present(job);
        JobAttempt open = JobAttempt.started(job.getId(), "worker-aaaaaaaa", 1, Instant.now());
        when(jobAttemptRepository.findByJobIdAndOutcomeOrderByAttemptNumberAsc(
                job.getId(), AttemptOutcome.IN_PROGRESS)).thenReturn(List.of(open));

        worker.handle(delivery(job.getId(), Priority.NORMAL, EnqueueSource.SUBMIT));

        assertThat(open.getOutcome()).isEqualTo(AttemptOutcome.ABANDONED);
        assertThat(open.getErrorMessage()).contains(CONSUMER).contains("worker-aaaaaaaa");
        verify(jobExecutor).execute(job);
        verify(consumer).acknowledge(STREAM, ENTRY);
    }

    @Test
    void reclaimingAdvancesTheAttemptNumberRatherThanResettingIt() {
        Job job = queued(Priority.NORMAL);
        job.markRunning();
        assertThat(job.getAttemptCount()).isEqualTo(1);
        present(job);

        worker.handle(delivery(job.getId(), Priority.NORMAL, EnqueueSource.SUBMIT));

        assertThat(job.getAttemptCount()).isEqualTo(2);
        ArgumentCaptor<JobAttempt> saved = ArgumentCaptor.forClass(JobAttempt.class);
        verify(jobAttemptRepository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues().get(0).getAttemptNumber()).isEqualTo(2);
        assertThat(saved.getAllValues().get(0).getWorkerId()).isEqualTo(CONSUMER);
    }

    @Test
    void anAttemptIsOpenedBeforeExecutionAndClosedAfterIt() throws Exception {
        Job job = queued(Priority.NORMAL);

        worker.handle(delivery(job.getId(), Priority.NORMAL, EnqueueSource.SUBMIT));

        ArgumentCaptor<JobAttempt> saved = ArgumentCaptor.forClass(JobAttempt.class);
        verify(jobAttemptRepository, times(2)).save(saved.capture());
        // the same row twice: opened IN_PROGRESS, then closed
        assertThat(saved.getAllValues().get(0)).isSameAs(saved.getAllValues().get(1));
        assertThat(saved.getAllValues().get(1).getOutcome()).isEqualTo(AttemptOutcome.SUCCESS);
        assertThat(saved.getAllValues().get(1).getFinishedAt()).isNotNull();
    }

    @Test
    void aFailedAttemptIsClosedAsFailureAndTheRetryIsScheduled() throws Exception {
        Job job = queued(Priority.HIGH);
        doThrow(new IllegalStateException("boom")).when(jobExecutor).execute(job);

        worker.handle(delivery(job.getId(), Priority.HIGH, EnqueueSource.SUBMIT));

        ArgumentCaptor<JobAttempt> saved = ArgumentCaptor.forClass(JobAttempt.class);
        verify(jobAttemptRepository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues().get(1).getOutcome()).isEqualTo(AttemptOutcome.FAILURE);
        assertThat(saved.getAllValues().get(1).getErrorMessage()).isEqualTo("boom");
        assertThat(job.getStatus()).isEqualTo(JobStatus.RETRYING);
        // the retry keeps the job's own tier, and the entry is acknowledged all the same
        verify(claims).retry(any(), eq("boom"), any());
        verify(consumer).acknowledge(STREAM, ENTRY);
    }

    @Test
    void anExhaustedFailureIsDeadLetteredAndStillAcknowledged() throws Exception {
        Job job = Job.create("always_fail", "", 1, Priority.LOW);
        present(job);
        doThrow(new IllegalStateException("boom")).when(jobExecutor).execute(job);

        worker.handle(new StreamDelivery("distroq:jobs:stream:low", ENTRY, job.getId(),
                Priority.LOW, fields(job.getId(), Priority.LOW, EnqueueSource.SUBMIT, 1L)));

        verify(deadLetterWriter).deadLetter(job, "boom");
        verify(claims, never()).retry(any(), any(), any());
        verify(consumer).acknowledge("distroq:jobs:stream:low", ENTRY);
    }

    @Test
    void aDeliveryForAJobThatIsNotInTheDatabaseIsAcknowledgedRatherThanLeftPending() {
        UUID unknown = UUID.randomUUID();
        when(jobRepository.findById(unknown)).thenReturn(Optional.empty());

        worker.handle(delivery(unknown, Priority.NORMAL, EnqueueSource.SUBMIT));

        verifyNoInteractions(jobExecutor);
        verify(consumer).acknowledge(STREAM, ENTRY);
    }

    @Test
    void aTierMismatchExecutesAtTheDatabaseTierRatherThanTheStreamTier() throws Exception {
        Job job = queued(Priority.LOW);

        // delivered on the high stream, but PostgreSQL says LOW
        worker.handle(new StreamDelivery("distroq:jobs:stream:high", ENTRY, job.getId(),
                Priority.HIGH, fields(job.getId(), Priority.HIGH, EnqueueSource.SUBMIT, 1L)));

        verify(jobExecutor).execute(job);
        assertThat(job.getPriority()).isEqualTo(Priority.LOW);
        verify(consumer).acknowledge("distroq:jobs:stream:high", ENTRY);
    }

    @Test
    void anEntryAlreadyBeingHandledByThisProcessIsNotHandledTwiceConcurrently() throws Exception {
        Job job = queued(Priority.NORMAL);
        StreamDelivery delivery = delivery(job.getId(), Priority.NORMAL, EnqueueSource.SUBMIT);
        doAnswer(invocation -> {
            assertThat(worker.isInFlight(STREAM, ENTRY)).isTrue();
            worker.handle(delivery);
            return null;
        }).when(jobExecutor).execute(job);

        worker.handle(delivery);

        verify(jobExecutor, times(1)).execute(job);
        verify(consumer, times(1)).acknowledge(STREAM, ENTRY);
        assertThat(worker.isInFlight(STREAM, ENTRY)).isFalse();
    }

    // ---- v0.6: user-scheduled deliveries ----

    @Test
    void aDueScheduledJobRunsItsFirstAttemptThroughTheNormalLifecycle() throws Exception {
        Instant scheduledAt = Instant.now().minusSeconds(1);
        Job job = scheduled(Priority.HIGH, scheduledAt);

        worker.handle(scheduledDelivery(job.getId(), Priority.HIGH));

        verify(jobExecutor).execute(job);
        assertThat(job.getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(job.getAttemptCount()).isEqualTo(1);
        // exactly one attempt row, opened then closed - promotion itself is not an attempt
        ArgumentCaptor<JobAttempt> saved = ArgumentCaptor.forClass(JobAttempt.class);
        verify(jobAttemptRepository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues().get(0)).isSameAs(saved.getAllValues().get(1));
        assertThat(saved.getAllValues().get(1).getAttemptNumber()).isEqualTo(1);
        verify(consumer).acknowledge(streamKey(Priority.HIGH), ENTRY);
    }

    @Test
    void theRequestedTimeIsStillOnTheJobAfterItHasRun() throws Exception {
        Instant scheduledAt = Instant.now().minusSeconds(1);
        Job job = scheduled(Priority.NORMAL, scheduledAt);

        worker.handle(scheduledDelivery(job.getId(), Priority.NORMAL));

        assertThat(job.getScheduledAt()).isEqualTo(scheduledAt);
    }

    @Test
    void anEntryThatArrivesBeforeItsTimeIsNeitherRunNorAcknowledged() throws Exception {
        // PostgreSQL holds what the submitter asked for and wins over the sorted-set score that
        // caused the promotion. Leaving the entry pending costs one reclaim cycle and is the only
        // option that neither executes early, nor loses the job, nor writes a second schedule
        Job job = scheduled(Priority.HIGH, Instant.now().plusSeconds(3600));

        worker.handle(scheduledDelivery(job.getId(), Priority.HIGH));

        verifyNoInteractions(jobExecutor);
        verify(consumer, never()).acknowledge(any(), any());
        verify(jobRepository, never()).save(any());
        verify(jobAttemptRepository, never()).save(any());
        verify(claims, never()).retry(any(), any(), any());
        assertThat(job.getStatus()).isEqualTo(JobStatus.SCHEDULED);
        assertThat(job.getAttemptCount()).isZero();
    }

    @Test
    void aScheduledJobWithNoRequestedTimeIsTreatedAsDueRatherThanStuckForever() throws Exception {
        // only reachable through a hand-written row, but "runs now" is a better failure than an
        // entry that is redelivered and refused for the rest of the stream's life
        Job job = scheduled(Priority.NORMAL, null);

        worker.handle(scheduledDelivery(job.getId(), Priority.NORMAL));

        verify(jobExecutor).execute(job);
        assertThat(job.getStatus()).isEqualTo(JobStatus.SUCCEEDED);
    }

    @Test
    void aScheduledJobThatFailsRetriesThroughTheRetrySetLikeAnyOther() throws Exception {
        Instant scheduledAt = Instant.now().minusSeconds(1);
        Job job = scheduled(Priority.LOW, scheduledAt);
        doThrow(new IllegalStateException("boom")).when(jobExecutor).execute(job);

        worker.handle(scheduledDelivery(job.getId(), Priority.LOW));

        assertThat(job.getStatus()).isEqualTo(JobStatus.RETRYING);
        // its own tier, on the retry set - never back onto the scheduled set
        verify(claims).retry(any(), eq("boom"), any());
        assertThat(job.getScheduledAt()).isEqualTo(scheduledAt);
        assertThat(job.getNextAttemptAt()).isNotNull().isNotEqualTo(scheduledAt);
        verify(consumer).acknowledge(streamKey(Priority.LOW), ENTRY);
    }

    @Test
    void aDuplicateScheduledDeliveryOfAFinishedJobAddsNoSecondAttempt() throws Exception {
        // promotion is at-least-once too: a redelivered SCHEDULED entry for a job that has already
        // run must not open a second attempt row
        Job job = scheduled(Priority.HIGH, Instant.now().minusSeconds(1));
        worker.handle(scheduledDelivery(job.getId(), Priority.HIGH));
        assertThat(job.getStatus()).isEqualTo(JobStatus.SUCCEEDED);

        worker.handle(scheduledDelivery(job.getId(), Priority.HIGH));

        verify(jobExecutor, times(1)).execute(job);
        verify(jobAttemptRepository, times(2)).save(any());
        verify(consumer, times(2)).acknowledge(streamKey(Priority.HIGH), ENTRY);
        assertThat(job.getAttemptCount()).isEqualTo(1);
    }

    @Test
    void aScheduledEntryForAJobThatIsAlreadyRetryingIsNotTreatedAsAFirstRun() throws Exception {
        // the entry that started this job is redelivered after the retry was scheduled. The
        // RETRYING rules own this, not the scheduling ones, and they supersede it
        Job job = Job.create("fail_n_times", "1", 3, Priority.NORMAL, Instant.now().plusSeconds(1));
        job.markQueuedFromSchedule();
        job.markRunning();
        job.markRetrying("boom", Instant.now().plusSeconds(30));
        present(job);

        worker.handle(scheduledDelivery(job.getId(), Priority.NORMAL));

        verifyNoInteractions(jobExecutor);
        verify(consumer).acknowledge(streamKey(Priority.NORMAL), ENTRY);
        assertThat(job.getStatus()).isEqualTo(JobStatus.RETRYING);
    }

    @Test
    void aScheduledJobIsRoutedByItsDatabaseTierWhenTheStreamDisagrees() throws Exception {
        Job job = scheduled(Priority.LOW, Instant.now().minusSeconds(1));

        worker.handle(new StreamDelivery(streamKey(Priority.HIGH), ENTRY, job.getId(),
                Priority.HIGH, fields(job.getId(), Priority.HIGH, EnqueueSource.SCHEDULED, 1L)));

        verify(jobExecutor).execute(job);
        assertThat(job.getPriority()).isEqualTo(Priority.LOW);
        verify(consumer).acknowledge(streamKey(Priority.HIGH), ENTRY);
    }

    private Job queued(Priority priority) {
        Job job = Job.create("sleep", "1", 3, priority);
        present(job);
        return job;
    }

    /**
     * Built in the future so the factory produces SCHEDULED, then the field is rewound to the
     * value under test. There is no setter, which is the point of the design being tested.
     */
    private Job scheduled(Priority priority, Instant scheduledAt) {
        Job job = Job.create("sleep", "1", 3, priority, Instant.now().plusSeconds(3600));
        try {
            Field field = Job.class.getDeclaredField("scheduledAt");
            field.setAccessible(true);
            field.set(job, scheduledAt);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        present(job);
        return job;
    }

    private Job retrying(Instant dueAt) {
        Job job = Job.create("sleep", "1", 3, Priority.NORMAL);
        job.markRunning();
        job.markRetrying("boom", dueAt);
        present(job);
        return job;
    }

    private void present(Job job) {
        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));
    }

    private static String streamKey(Priority priority) {
        return "distroq:jobs:stream:" + priority.keySuffix();
    }

    private static StreamDelivery delivery(UUID jobId, Priority priority, EnqueueSource source) {
        return new StreamDelivery(STREAM, ENTRY, jobId, priority, fields(jobId, priority, source, 1L));
    }

    private static StreamDelivery scheduledDelivery(UUID jobId, Priority priority) {
        return new StreamDelivery(streamKey(priority), ENTRY, jobId, priority,
                fields(jobId, priority, EnqueueSource.SCHEDULED, System.currentTimeMillis()));
    }

    private static Map<String, String> fields(UUID jobId, Priority priority, EnqueueSource source,
                                              long enqueuedAt) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(JobStreamEntry.FIELD_JOB_ID, jobId.toString());
        fields.put(JobStreamEntry.FIELD_PRIORITY, priority.name());
        fields.put(JobStreamEntry.FIELD_ENQUEUED_AT, Long.toString(enqueuedAt));
        fields.put(JobStreamEntry.FIELD_SOURCE, source.name());
        return fields;
    }
}
