package com.distroq.worker;

import com.distroq.TestProperties;
import com.distroq.model.AttemptOutcome;
import com.distroq.model.Job;
import com.distroq.model.JobAttempt;
import com.distroq.model.Priority;
import com.distroq.outbox.OutboxService;
import com.distroq.repository.DeadLetterRepository;
import com.distroq.repository.JobAttemptRepository;
import com.distroq.repository.JobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExecutionClaimServiceTest {

    private JobRepository jobs;
    private JobAttemptRepository attempts;
    private ExecutionClaimService service;

    @BeforeEach
    void setUp() {
        jobs = mock(JobRepository.class);
        attempts = mock(JobAttemptRepository.class);
        service = new ExecutionClaimService(jobs, attempts, mock(DeadLetterRepository.class),
                mock(OutboxService.class), TestProperties.defaults());
        when(attempts.findByJobIdAndOutcomeOrderByAttemptNumberAsc(any(), any()))
                .thenReturn(List.of());
        when(attempts.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void oneWorkerWinsAQueuedJobClaim() {
        Job job = Job.create("sleep", "1", 3, Priority.NORMAL);
        when(jobs.claimExecution(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            job.markRunning();
            return 1;
        });
        when(jobs.findById(job.getId())).thenReturn(Optional.of(job));

        Optional<ExecutionClaimService.Claim> claim = service.claim(job.getId(), "worker-a", "entry 1-0");

        assertThat(claim).isPresent();
        assertThat(claim.orElseThrow().attempt().getWorkerId()).isEqualTo("worker-a");
    }

    @Test
    void losingWorkerCreatesNoAttempt() {
        when(jobs.claimExecution(any(), any(), any(), any(), any())).thenReturn(0);

        assertThat(service.claim(java.util.UUID.randomUUID(), "worker-b", "entry 1-0")).isEmpty();
        verify(attempts, never()).save(any());
    }

    @Test
    void onlyTheOwnerCanRenew() {
        ExecutionClaimService.Claim claim = claim("worker-a");
        when(jobs.renewExecutionLease(any(), any(), any(), any(), any())).thenReturn(1, 0);

        assertThat(service.renew(claim)).isTrue();
        assertThat(service.renew(claim)).isFalse();
    }

    @Test
    void rejectedFinalizationDoesNotCloseTheAttempt() {
        ExecutionClaimService.Claim claim = claim("worker-old");
        when(jobs.completeExecution(any(), any(), any(), any())).thenReturn(0);

        assertThat(service.succeed(claim)).isFalse();
        assertThat(claim.attempt().getOutcome()).isEqualTo(AttemptOutcome.IN_PROGRESS);
        verify(attempts, never()).save(any());
    }

    private ExecutionClaimService.Claim claim(String owner) {
        Job job = Job.create("sleep", "1", 3, Priority.NORMAL);
        job.markRunning();
        return new ExecutionClaimService.Claim(job,
                JobAttempt.started(job.getId(), owner, job.getAttemptCount(), Instant.now()));
    }
}