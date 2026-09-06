package com.distroq.worker;

import com.distroq.model.DeadLetter;
import com.distroq.model.Job;
import com.distroq.model.JobStatus;
import com.distroq.repository.DeadLetterRepository;
import com.distroq.repository.JobRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeadLetterWriterTest {

    private final JobRepository jobRepository = mock(JobRepository.class);
    private final DeadLetterRepository deadLetterRepository = mock(DeadLetterRepository.class);
    private final DeadLetterWriter writer = new DeadLetterWriter(jobRepository, deadLetterRepository);

    @Test
    void firstExhaustionInsertsAFreshRow() {
        Job job = exhaustedJob();
        when(deadLetterRepository.findById(job.getId())).thenReturn(Optional.empty());
        when(deadLetterRepository.save(any(DeadLetter.class))).thenAnswer(i -> i.getArgument(0));

        writer.deadLetter(job, "boom");

        assertThat(job.getStatus()).isEqualTo(JobStatus.DEAD_LETTERED);
        DeadLetter saved = capturedDeadLetter();
        assertThat(saved.getReplayCount()).isZero();
        assertThat(saved.isReplayed()).isFalse();
    }

    @Test
    void reExhaustionUpdatesTheExistingRowInsteadOfInsertingASecond() {
        Job job = exhaustedJob();
        DeadLetter existing = DeadLetter.of(job.getId(), "boom");
        existing.markReplayed();
        when(deadLetterRepository.findById(job.getId())).thenReturn(Optional.of(existing));
        when(deadLetterRepository.save(any(DeadLetter.class))).thenAnswer(i -> i.getArgument(0));

        writer.deadLetter(job, "boom again");

        DeadLetter saved = capturedDeadLetter();
        assertThat(saved).isSameAs(existing);
        assertThat(saved.getReplayCount()).isEqualTo(1);
        assertThat(saved.isReplayed()).isFalse();
        assertThat(saved.getFinalError()).isEqualTo("boom again");
    }

    private DeadLetter capturedDeadLetter() {
        ArgumentCaptor<DeadLetter> captor = ArgumentCaptor.forClass(DeadLetter.class);
        verify(deadLetterRepository).save(captor.capture());
        return captor.getValue();
    }

    private Job exhaustedJob() {
        Job job = Job.create("always_fail", "", 1);
        job.markRunning();
        return job;
    }
}
