package com.distroq.effects;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-local effect counters.
 *
 * <p>Both are events rather than states, so neither can be derived from a table after the fact: a
 * deduplication hit leaves no row behind, precisely because its whole job is to leave nothing
 * behind. They reset when the process restarts and they are per-instance. The exact,
 * database-derived companion is {@code effectApplications}, which counts COMPLETED ledger rows.
 */
@Component
public class EffectMetrics {

    private final AtomicLong deduplicationHits = new AtomicLong();
    private final AtomicLong applications = new AtomicLong();

    public void deduplicationHit() { deduplicationHits.incrementAndGet(); }
    public void applied() { applications.incrementAndGet(); }
    public long deduplicationHits() { return deduplicationHits.get(); }
    public long applications() { return applications.get(); }
}
