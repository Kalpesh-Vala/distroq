package com.distroq.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Durable counter behind the built-in {@code idempotent_counter} job type. */
@Entity
@Table(name = "effect_counters")
public class EffectCounter {

    @Id
    @Column(name = "counter_name", length = 255)
    private String counterName;

    @Column(nullable = false)
    private long counterValue;

    @Column(nullable = false)
    private Instant updatedAt;

    protected EffectCounter() {
        // for JPA
    }

    public String getCounterName() { return counterName; }
    public long getCounterValue() { return counterValue; }
    public Instant getUpdatedAt() { return updatedAt; }
}
