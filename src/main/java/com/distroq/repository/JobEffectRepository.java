package com.distroq.repository;

import com.distroq.model.EffectStatus;
import com.distroq.model.JobEffect;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JobEffectRepository extends JpaRepository<JobEffect, String> {

    /**
     * Claim an effect key by inserting it, or lose the race and return 0.
     *
     * <p>Native, and deliberately not a find-then-save: the whole guarantee is that PostgreSQL
     * arbitrates, not the application. Two transactions running this at once serialise on the
     * primary key — the second blocks until the first commits and then sees the row.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT INTO job_effects
                (effect_key, job_id, attempt_number, effect_type, status, created_at)
            VALUES (:effectKey, :jobId, :attemptNumber, :effectType, 'STARTED', :now)
            ON CONFLICT (effect_key) DO NOTHING
            """, nativeQuery = true)
    int claim(@Param("effectKey") String effectKey, @Param("jobId") UUID jobId,
              @Param("attemptNumber") int attemptNumber, @Param("effectType") String effectType,
              @Param("now") Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select effect from JobEffect effect where effect.effectKey = :effectKey")
    Optional<JobEffect> lockByKey(@Param("effectKey") String effectKey);

    List<JobEffect> findByJobIdOrderByCreatedAtAsc(UUID jobId);

    long countByStatus(EffectStatus status);

    @Query("select effect from JobEffect effect where effect.status = 'STARTED' "
            + "and effect.createdAt < :before order by effect.createdAt")
    List<JobEffect> staleStarted(@Param("before") Instant before, Pageable page);

    @Query("select count(effect) from JobEffect effect where effect.status = 'STARTED' "
            + "and effect.createdAt < :before")
    long countStaleStarted(@Param("before") Instant before);
}
