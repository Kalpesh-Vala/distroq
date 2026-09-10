package com.distroq.api;

import com.distroq.TestProperties;
import com.distroq.api.dto.SubmitJobRequest;
import com.distroq.model.IdempotencyKey;
import com.distroq.model.Job;
import com.distroq.outbox.OutboxEventType;
import com.distroq.outbox.OutboxService;
import com.distroq.repository.DeadLetterRepository;
import com.distroq.repository.IdempotencyKeyRepository;
import com.distroq.repository.JobRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JobSubmissionServiceTest {

    private JobRepository jobs;
    private IdempotencyKeyRepository keys;
    private OutboxService outbox;
    private JobSubmissionService service;

    @BeforeEach
    void setUp() {
        jobs = mock(JobRepository.class);
        keys = mock(IdempotencyKeyRepository.class);
        outbox = mock(OutboxService.class);
        EntityManager entityManager = mock(EntityManager.class);
        Query lockQuery = mock(Query.class);
        when(entityManager.createNativeQuery(any())).thenReturn(lockQuery);
        when(lockQuery.setParameter(any(Integer.class), any())).thenReturn(lockQuery);
        when(lockQuery.getSingleResult()).thenReturn(null);
        when(jobs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(keys.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        service = new JobSubmissionService(jobs, mock(DeadLetterRepository.class), keys, outbox,
                new IdempotencyRequestHasher(
                        com.fasterxml.jackson.databind.json.JsonMapper.builder()
                                .findAndAddModules().build()),
                entityManager,
                new com.distroq.metrics.DistroqMetrics(
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                        TestProperties.defaults()),
                TestProperties.defaults());
    }

    @Test
    void firstKeyedRequestCreatesOneJobKeyAndOutboxEvent() {
        when(keys.findById("customer-123")).thenReturn(Optional.empty());

        JobSubmissionService.SubmissionResult result =
                service.submit(immediate("500"), " customer-123 ");

        assertThat(result.replayed()).isFalse();
        verify(jobs).save(any(Job.class));
        verify(keys).save(any(IdempotencyKey.class));
        verify(outbox).create(eq(result.job()), eq(OutboxEventType.ENQUEUE_SUBMIT), any(), eq(null));
    }

    @Test
    void identicalReplayReturnsOriginalWithoutWriting() {
        Job original = Job.create("sleep", "500", 3);
        String hash = new IdempotencyRequestHasher(
                com.fasterxml.jackson.databind.json.JsonMapper.builder().findAndAddModules().build())
                .hash("sleep", "500", 3, original.getPriority(), null);
        when(keys.findById("customer-123"))
                .thenReturn(Optional.of(IdempotencyKey.create("customer-123", hash, original.getId())));
        when(jobs.findById(original.getId())).thenReturn(Optional.of(original));

        JobSubmissionService.SubmissionResult result = service.submit(immediate("500"), "customer-123");

        assertThat(result.replayed()).isTrue();
        assertThat(result.job().getId()).isEqualTo(original.getId());
        verify(jobs, never()).save(any());
        verify(keys, never()).save(any());
        verify(outbox, never()).create(any(), any(), any(), any());
    }

    @Test
    void conflictingReplayReturns409WithoutWriting() {
        Job original = Job.create("sleep", "500", 3);
        when(keys.findById("customer-123")).thenReturn(Optional.of(
                IdempotencyKey.create("customer-123", "different-hash", original.getId())));

        assertThatThrownBy(() -> service.submit(immediate("9999"), "customer-123"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        failure -> {
                            assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                            assertThat(failure.getReason()).contains("customer-123");
                        });
        verify(jobs, never()).save(any());
        verify(outbox, never()).create(any(), any(), any(), any());
    }

    @Test
    void invalidKeysReturn400() {
        assertThatThrownBy(() -> service.submit(immediate("500"), "   "))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.submit(immediate("500"), "x".repeat(129)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void missingKeyPreservesSubmissionWithoutIdempotencyRow() {
        service.submit(immediate("500"), null);

        verify(jobs).save(any());
        verify(keys, never()).save(any());
        verify(outbox).create(any(), eq(OutboxEventType.ENQUEUE_SUBMIT), any(), eq(null));
    }

    @Test
    void futureRequestCreatesOnlyScheduledOutboxIntent() {
        SubmitJobRequest request = new SubmitJobRequest("sleep", "500", null, "HIGH",
                "2099-09-07T15:30:00Z");

        service.submit(request, null);

        verify(outbox).create(any(), eq(OutboxEventType.SCHEDULE_USER_JOB), any(),
                eq(java.time.Instant.parse("2099-09-07T15:30:00Z")));
        verify(outbox, times(1)).create(any(), any(), any(), any());
    }

    private SubmitJobRequest immediate(String payload) {
        return new SubmitJobRequest("sleep", payload, null, "NORMAL", null);
    }
}