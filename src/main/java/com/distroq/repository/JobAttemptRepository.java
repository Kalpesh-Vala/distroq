package com.distroq.repository;

import com.distroq.model.AttemptOutcome;
import com.distroq.model.JobAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface JobAttemptRepository extends JpaRepository<JobAttempt, UUID> {

    List<JobAttempt> findByJobIdOrderByAttemptNumberAsc(UUID jobId);

    /** Attempts left open by a worker that never reported back. Served by idx_job_attempts_job_id. */
    List<JobAttempt> findByJobIdAndOutcomeOrderByAttemptNumberAsc(UUID jobId, AttemptOutcome outcome);

    long countByOutcome(AttemptOutcome outcome);
}
