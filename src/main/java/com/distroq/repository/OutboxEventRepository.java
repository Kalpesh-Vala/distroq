package com.distroq.repository;

import com.distroq.model.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    @Query(value = """
            SELECT * FROM outbox_events
            WHERE published_at IS NULL
              AND attempt_count < :maxAttempts
              AND (locked_until IS NULL OR locked_until < :now)
              AND available_at <= :now
            ORDER BY created_at
            FOR UPDATE SKIP LOCKED
            LIMIT :batchSize
            """, nativeQuery = true)
    List<OutboxEvent> claimable(@Param("now") Instant now,
                                @Param("maxAttempts") int maxAttempts,
                                @Param("batchSize") int batchSize);

    @Query("select count(event) from OutboxEvent event where event.publishedAt is null "
            + "and event.availableAt <= :now and event.attemptCount < :maxAttempts "
            + "and (event.lockedUntil is null or event.lockedUntil < :now)")
    long countPending(@Param("now") Instant now, @Param("maxAttempts") int maxAttempts);

    @Query("select count(event) from OutboxEvent event where event.publishedAt is null "
            + "and event.attemptCount > 0 and event.attemptCount < :maxAttempts")
    long countRetryableFailures(@Param("maxAttempts") int maxAttempts);

    @Query("select count(event) from OutboxEvent event where event.publishedAt is null "
            + "and event.attemptCount >= :maxAttempts")
    long countTerminalFailures(@Param("maxAttempts") int maxAttempts);

    @Query("select min(event.createdAt) from OutboxEvent event where event.publishedAt is null")
    Instant oldestUnpublishedCreatedAt();

    long countByPublishedAtIsNotNull();
}