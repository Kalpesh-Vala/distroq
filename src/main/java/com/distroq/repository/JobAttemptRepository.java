package com.distroq.repository;

import com.distroq.model.AttemptOutcome;
import com.distroq.model.JobAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface JobAttemptRepository extends JpaRepository<JobAttempt, UUID> {

    List<JobAttempt> findByJobIdOrderByAttemptNumberAsc(UUID jobId);

    /** Attempts left open by a worker that never reported back. Served by idx_job_attempts_job_id. */
    List<JobAttempt> findByJobIdAndOutcomeOrderByAttemptNumberAsc(UUID jobId, AttemptOutcome outcome);

    long countByOutcome(AttemptOutcome outcome);

    @Query("select count(attempt) from JobAttempt attempt where attempt.jobId = :jobId "
            + "and attempt.outcome = com.distroq.model.AttemptOutcome.IN_PROGRESS")
    long countInProgress(@Param("jobId") UUID jobId);
}
