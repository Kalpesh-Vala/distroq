package com.distroq.worker;

import com.distroq.model.DeadLetter;
import com.distroq.model.Job;
import com.distroq.repository.DeadLetterRepository;
import com.distroq.repository.JobRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The exhaustion path's two database writes, in one transaction.
 *
 * <p>A separate bean rather than a {@code @Transactional} method on {@link Worker}: Spring's
 * transaction advice is proxy-based, so a self-call from {@code handleFailure} would silently
 * run without a transaction.
 *
 * <p>This makes the two <em>database</em> writes atomic with each other. It does not address the
 * dual write — Redis is not a transaction participant. See NOTES.md.
 */
@Component
public class DeadLetterWriter {

    private final JobRepository jobRepository;
    private final DeadLetterRepository deadLetterRepository;

    public DeadLetterWriter(JobRepository jobRepository, DeadLetterRepository deadLetterRepository) {
        this.jobRepository = jobRepository;
        this.deadLetterRepository = deadLetterRepository;
    }

    @Transactional
    public void deadLetter(Job job, String finalError) {
        job.markDeadLettered(finalError);
        jobRepository.save(job);

        // a replayed job that fails again already has a row; inserting would violate the PK
        DeadLetter deadLetter = deadLetterRepository.findById(job.getId())
                .map(existing -> {
                    existing.markDeadLetteredAgain(finalError);
                    return existing;
                })
                .orElseGet(() -> DeadLetter.of(job.getId(), finalError));
        deadLetterRepository.save(deadLetter);
    }
}
