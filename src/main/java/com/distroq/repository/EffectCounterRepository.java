package com.distroq.repository;

import com.distroq.model.EffectCounter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface EffectCounterRepository extends JpaRepository<EffectCounter, String> {

    /**
     * Upsert-and-increment in one statement. A read-modify-write would need a row lock the caller
     * would have to remember to take; this needs nothing remembered.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT INTO effect_counters (counter_name, counter_value, updated_at)
            VALUES (:name, 1, :now)
            ON CONFLICT (counter_name) DO UPDATE
                SET counter_value = effect_counters.counter_value + 1, updated_at = :now
            """, nativeQuery = true)
    int increment(@Param("name") String name, @Param("now") Instant now);

    @Query(value = "SELECT counter_value FROM effect_counters WHERE counter_name = :name",
            nativeQuery = true)
    Long currentValue(@Param("name") String name);
}
