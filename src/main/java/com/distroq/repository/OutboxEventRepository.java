package com.distroq.repository;

import com.distroq.model.OutboxEvent;
import com.distroq.model.OutboxStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository
        extends JpaRepository<OutboxEvent, UUID>, JpaSpecificationExecutor<OutboxEvent> {

    /**
     * PENDING rows, plus PUBLISHING rows whose relay lease expired.
     *
     * <p>The second half is what keeps the relay self-healing without reconciliation: a process
     * that dies mid-publication leaves a PUBLISHING row behind, and once its lease runs out the
     * next relay tick simply takes it. Reconciliation reports the same rows so an operator can
     * see that a crash happened, but nothing depends on reconciliation running for the queue to
     * drain.
     *
     * <p>FAILED is absent by design. A terminal event is never claimed again until an operator
     * says so, which is the difference between v0.8 and a retry loop that hides the problem.
     */
    @Query(value = """
            SELECT * FROM outbox_events
            WHERE (status = 'PENDING' OR (status = 'PUBLISHING' AND locked_until < :now))
              AND available_at <= :now
            ORDER BY created_at
            FOR UPDATE SKIP LOCKED
            LIMIT :batchSize
            """, nativeQuery = true)
    List<OutboxEvent> claimable(@Param("now") Instant now, @Param("batchSize") int batchSize);

    long countByStatus(OutboxStatus status);

    @Query("select count(event) from OutboxEvent event where event.status = 'PENDING' "
            + "and event.availableAt <= :now "
            + "and (event.lockedUntil is null or event.lockedUntil < :now)")
    long countPending(@Param("now") Instant now);

    /** PENDING and already failed at least once: still inside its budget, still being retried. */
    @Query("select count(event) from OutboxEvent event where event.status = 'PENDING' "
            + "and event.attemptCount > 0")
    long countRetryableFailures();

    @Query("select min(event.createdAt) from OutboxEvent event where event.publishedAt is null")
    Instant oldestUnpublishedCreatedAt();

    long countByPublishedAtIsNotNull();

    // ------------------------------------------------------------------- reconciliation queries

    @Query("select event from OutboxEvent event where event.status = 'PENDING' "
            + "and event.createdAt < :before order by event.createdAt")
    List<OutboxEvent> stalePending(@Param("before") Instant before, Pageable page);

    @Query("select count(event) from OutboxEvent event where event.status = 'PENDING' "
            + "and event.createdAt < :before")
    long countStalePending(@Param("before") Instant before);

    @Query("select event from OutboxEvent event where event.status = 'PUBLISHING' "
            + "and event.lockedUntil < :now order by event.lockedUntil")
    List<OutboxEvent> expiredLocks(@Param("now") Instant now, Pageable page);

    @Query("select count(event) from OutboxEvent event where event.status = 'PUBLISHING' "
            + "and event.lockedUntil < :now")
    long countExpiredLocks(@Param("now") Instant now);

    List<OutboxEvent> findByStatusOrderByTerminalFailedAtAsc(OutboxStatus status, Pageable page);

    /**
     * Rows whose bookkeeping disagrees with itself: published but not PUBLISHED, or PUBLISHED
     * with no publication timestamp. Neither is reachable through the relay, so finding one means
     * something wrote the table directly.
     */
    @Query("select event from OutboxEvent event where "
            + "(event.publishedAt is not null and event.status <> 'PUBLISHED') "
            + "or (event.status = 'PUBLISHED' and event.publishedAt is null) "
            + "order by event.createdAt")
    List<OutboxEvent> inconsistentPublished(Pageable page);

    @Query("select event from OutboxEvent event where event.aggregateId is not null "
            + "and not exists (select 1 from Job job where job.id = event.aggregateId) "
            + "order by event.createdAt")
    List<OutboxEvent> orphaned(Pageable page);

    @Query("select event from OutboxEvent event where event.aggregateId = :jobId "
            + "and event.eventType = com.distroq.outbox.OutboxEventType.SCHEDULE_USER_JOB")
    List<OutboxEvent> scheduleEventsFor(@Param("jobId") UUID jobId);

    @Query("select event from OutboxEvent event where event.aggregateId = :jobId "
            + "and event.eventType = com.distroq.outbox.OutboxEventType.SCHEDULE_RETRY")
    List<OutboxEvent> retryEventsFor(@Param("jobId") UUID jobId);

    // ---------------------------------------------------------------------- retention cleanup

    /**
     * PUBLISHED rows that are safe to delete.
     *
     * <p>Three conditions, all of which have to hold. The row is past its retention window; no
     * sibling event for the same job is FAILED or mid-publication; and the job itself is not
     * currently in one of the states reconciliation reports as an unresolved finding. The last
     * two are what stop cleanup deleting the evidence an operator is in the middle of reading.
     *
     * <p>PENDING, PUBLISHING and FAILED cannot appear here at all. That is not an optimisation —
     * it is the retention policy, expressed where it cannot be forgotten.
     */
    @Query(value = """
            SELECT * FROM outbox_events published
            WHERE published.status = 'PUBLISHED'
              AND published.published_at < :before
              AND NOT EXISTS (
                  SELECT 1 FROM outbox_events sibling
                  WHERE sibling.aggregate_id = published.aggregate_id
                    AND sibling.status IN ('FAILED', 'PUBLISHING')
              )
              AND NOT EXISTS (
                  SELECT 1 FROM jobs job
                  WHERE job.id = published.aggregate_id
                    AND (
                        (job.status = 'SCHEDULED' AND job.scheduled_at < :staleBefore)
                        OR (job.status = 'RETRYING' AND job.next_attempt_at < :staleBefore)
                        OR (job.status = 'RUNNING' AND job.execution_lease_until < :staleBefore)
                    )
              )
            ORDER BY published.published_at
            LIMIT :batchSize
            """, nativeQuery = true)
    List<OutboxEvent> deletablePublished(@Param("before") Instant before,
                                         @Param("staleBefore") Instant staleBefore,
                                         @Param("batchSize") int batchSize);
}