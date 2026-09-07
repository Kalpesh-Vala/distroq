package com.distroq.model;

public enum JobStatus {
    /**
     * Waiting for a user-requested execution time that has not arrived yet, in the scheduled
     * sorted set. Distinct from {@link #RETRYING} on purpose: this job has never run, so counting
     * it as backlog or as a failure would both be wrong. See NOTES.md.
     */
    SCHEDULED,
    QUEUED,
    RUNNING,
    /** Failed with attempts remaining; waiting in the delayed set for its backoff window. */
    RETRYING,
    SUCCEEDED,
    /**
     * Transient per-attempt outcome, no longer a resting state: exhausted retries go to
     * {@link #DEAD_LETTERED}. Retained because v0.2 rows still carry it and are not migrated.
     */
    FAILED,
    /** Terminal: attempts exhausted and the job was moved to the dead-letter queue. */
    DEAD_LETTERED
}
