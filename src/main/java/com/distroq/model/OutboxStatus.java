package com.distroq.model;

/**
 * Explicit outbox lifecycle, replacing v0.7's inference from nullable columns.
 *
 * <p>PostgreSQL is the source of truth for this value. Redis holds a deduplication marker, not a
 * state machine, and a marker that has expired says nothing about whether publication happened.
 */
public enum OutboxStatus {

    /** Eligible for the relay to claim. The initial state, and the state an operator retry restores. */
    PENDING,

    /** Claimed by a relay lease. Reverts to PENDING by itself once {@code lockedUntil} passes. */
    PUBLISHING,

    /** Redis publication succeeded. Terminal, and the only state cleanup may ever delete. */
    PUBLISHED,

    /**
     * The relay budget is exhausted. Terminal until an operator says otherwise: the relay will not
     * claim it again, and nothing deletes it, so the evidence survives for inspection.
     */
    FAILED
}
