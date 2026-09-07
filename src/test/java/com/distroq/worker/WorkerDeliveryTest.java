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
    private Worker worker;

    @BeforeEach
    void setUp() {
        jobQueue = mock(JobQueue.class);
        consumer = mock(JobStreamConsumer.class);
        jobRepository = mock(JobRepository.class);
        jobAttemptRepository = mock(JobAttemptRepository.class);
        jobExecutor = mock(JobExecutor.class);
        deadLetterWriter = mock(DeadLetterWriter.class);

        when(consumer.consumerName()).thenReturn(CONSUMER);
        when(jobRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(jobAttemptRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(jobAttemptRepository.findByJobIdAndOutcomeOrderByAttemptNumberAsc(any(), any()))
                .thenReturn(List.of());

        worker = new Worker(jobQueue, consumer, jobRepository, jobAttemptRepository, jobExecutor,
                new BackoffPolicy(TestProperties.defaults()), deadLetterWriter,
                new PriorityStrategy(TestProperties.defaults()));
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
        verify(jobQueue).scheduleAt(eq(job.getId()), eq(Priority.HIGH), any());
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
        verify(jobQueue, never()).scheduleAt(any(), any(), any());
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

    private Job queued(Priority priority) {
        Job job = Job.create("sleep", "1", 3, priority);
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

    private static StreamDelivery delivery(UUID jobId, Priority priority, EnqueueSource source) {
        return new StreamDelivery(STREAM, ENTRY, jobId, priority, fields(jobId, priority, source, 1L));
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
