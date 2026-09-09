package com.distroq.repository;

import com.distroq.model.Job;
import com.distroq.model.JobStatus;
import com.distroq.model.Priority;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface JobRepository extends JpaRepository<Job, UUID> {

        @Modifying(clearAutomatically = true, flushAutomatically = true)
        @Query(value = """
                        UPDATE jobs
                        SET status = 'RUNNING', execution_owner = :owner,
                                execution_lease_until = :leaseUntil, active_attempt_id = :attemptId,
                                attempt_count = attempt_count + 1, started_at = :now, updated_at = :now,
                                next_attempt_at = NULL, version = version + 1
                        WHERE id = :jobId
                            AND (
                                status = 'QUEUED'
                                OR (status = 'SCHEDULED' AND (scheduled_at IS NULL OR scheduled_at <= :now))
                                OR (status = 'RETRYING' AND (next_attempt_at IS NULL OR next_attempt_at <= :now))
                                OR (status = 'RUNNING' AND execution_lease_until < :now)
                            )
                            AND (execution_owner IS NULL OR execution_lease_until < :now)
                        """, nativeQuery = true)
        int claimExecution(@Param("jobId") UUID jobId, @Param("owner") String owner,
                                             @Param("attemptId") UUID attemptId, @Param("now") Instant now,
                                             @Param("leaseUntil") Instant leaseUntil);

        @Modifying(clearAutomatically = true, flushAutomatically = true)
        @Query(value = """
                        UPDATE jobs SET execution_lease_until = :leaseUntil, updated_at = :now,
                                version = version + 1
                        WHERE id = :jobId AND status = 'RUNNING' AND execution_owner = :owner
                            AND active_attempt_id = :attemptId AND execution_lease_until >= :now
                        """, nativeQuery = true)
        int renewExecutionLease(@Param("jobId") UUID jobId, @Param("owner") String owner,
                                                        @Param("attemptId") UUID attemptId, @Param("now") Instant now,
                                                        @Param("leaseUntil") Instant leaseUntil);

        @Modifying(clearAutomatically = true, flushAutomatically = true)
        @Query(value = """
                        UPDATE jobs
                        SET status = 'SUCCEEDED', finished_at = :now, updated_at = :now,
                                next_attempt_at = NULL, execution_owner = NULL,
                                execution_lease_until = NULL, active_attempt_id = NULL, version = version + 1
                        WHERE id = :jobId AND status = 'RUNNING' AND execution_owner = :owner
                            AND active_attempt_id = :attemptId AND execution_lease_until >= :now
                        """, nativeQuery = true)
        int completeExecution(@Param("jobId") UUID jobId, @Param("owner") String owner,
                                                    @Param("attemptId") UUID attemptId, @Param("now") Instant now);

        @Modifying(clearAutomatically = true, flushAutomatically = true)
        @Query(value = """
                        UPDATE jobs
                        SET status = 'RETRYING', error_message = :error, next_attempt_at = :dueAt,
                                updated_at = :now, execution_owner = NULL, execution_lease_until = NULL,
                                active_attempt_id = NULL, version = version + 1
                        WHERE id = :jobId AND status = 'RUNNING' AND execution_owner = :owner
                            AND active_attempt_id = :attemptId AND execution_lease_until >= :now
                        """, nativeQuery = true)
        int scheduleRetry(@Param("jobId") UUID jobId, @Param("owner") String owner,
                                            @Param("attemptId") UUID attemptId, @Param("error") String error,
                                            @Param("dueAt") Instant dueAt, @Param("now") Instant now);

        @Modifying(clearAutomatically = true, flushAutomatically = true)
        @Query(value = """
                        UPDATE jobs
                        SET status = 'DEAD_LETTERED', error_message = :error, finished_at = :now,
                                updated_at = :now, next_attempt_at = NULL, execution_owner = NULL,
                                execution_lease_until = NULL, active_attempt_id = NULL, version = version + 1
                        WHERE id = :jobId AND status = 'RUNNING' AND execution_owner = :owner
                            AND active_attempt_id = :attemptId AND execution_lease_until >= :now
                        """, nativeQuery = true)
        int deadLetterExecution(@Param("jobId") UUID jobId, @Param("owner") String owner,
                                                        @Param("attemptId") UUID attemptId, @Param("error") String error,
                                                        @Param("now") Instant now);

        @Query(value = "SELECT count(*) FROM jobs WHERE execution_owner IS NOT NULL "
                        + "AND execution_lease_until > :now", nativeQuery = true)
        long countActiveLeases(@Param("now") Instant now);

        // ----------------------------------------------------------------- v0.8 reconciliation

        @Query("select job from Job job where job.status = com.distroq.model.JobStatus.SCHEDULED "
                        + "and job.scheduledAt < :before order by job.scheduledAt")
        List<Job> staleScheduled(@Param("before") Instant before, Pageable page);

        @Query("select count(job) from Job job where job.status = com.distroq.model.JobStatus.SCHEDULED "
                        + "and job.scheduledAt < :before")
        long countStaleScheduled(@Param("before") Instant before);

        @Query("select job from Job job where job.status = com.distroq.model.JobStatus.RETRYING "
                        + "and job.nextAttemptAt < :before order by job.nextAttemptAt")
        List<Job> staleRetrying(@Param("before") Instant before, Pageable page);

        @Query("select count(job) from Job job where job.status = com.distroq.model.JobStatus.RETRYING "
                        + "and job.nextAttemptAt < :before")
        long countStaleRetrying(@Param("before") Instant before);

        @Query("select job from Job job where job.status = com.distroq.model.JobStatus.RUNNING "
                        + "and job.executionLeaseUntil < :before order by job.executionLeaseUntil")
        List<Job> expiredLeases(@Param("before") Instant before, Pageable page);

        @Query("select count(job) from Job job where job.status = com.distroq.model.JobStatus.RUNNING "
                        + "and job.executionLeaseUntil < :before")
        long countExpiredLeases(@Param("before") Instant before);

        /**
         * Jobs that finished but kept their lease bookkeeping. Not reachable through the claim
         * service, which clears all three columns in the same statement that sets the terminal
         * status, so a row here means an out-of-band write.
         */
        @Query("select job from Job job where job.status in (com.distroq.model.JobStatus.SUCCEEDED, "
                        + "com.distroq.model.JobStatus.FAILED, com.distroq.model.JobStatus.DEAD_LETTERED) "
                        + "and (job.executionOwner is not null or job.activeAttemptId is not null) "
                        + "order by job.updatedAt")
        List<Job> terminalWithLease(Pageable page);

    List<Job> findTop50ByOrderByCreatedAtDesc();

    List<Job> findTop50ByStatusOrderByCreatedAtDesc(JobStatus status);

    List<Job> findTop50ByPriorityOrderByCreatedAtDesc(Priority priority);

    /** Combined filter pushed into SQL; filtering a 50-row page in memory would silently truncate. */
    List<Job> findTop50ByStatusAndPriorityOrderByCreatedAtDesc(JobStatus status, Priority priority);
}
