package com.distroq.model;

/**
 * Outcome of one execution attempt.
 *
 * <p>v0.5 added the two non-terminal cases. Before Streams, an attempt row was only written once
 * the attempt had finished, so a worker that vanished mid-job left no trace at all — the job sat
 * RUNNING forever with no record of who had been running it. A row is now inserted as
 * {@link #IN_PROGRESS} before execution starts and updated in place.
 *
 * <p>{@link #ABANDONED} means the worker did not report a success or a failure before the delivery
 * was reclaimed. It does <em>not</em> mean the job did nothing: the side effects of an abandoned
 * attempt are unknown, which is exactly why idempotency keys are needed before duplicate execution
 * can be made safe.
 *
 * <p>No DDL was needed for either value. V2 dropped the CHECK constraint Hibernate had generated
 * over this enum, for precisely this reason.
 */
public enum AttemptOutcome {
    IN_PROGRESS,
    SUCCESS,
    FAILURE,
    ABANDONED
}
