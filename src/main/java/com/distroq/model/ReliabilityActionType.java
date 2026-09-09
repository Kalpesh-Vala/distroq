package com.distroq.model;

public enum ReliabilityActionType {

    /** An operator re-armed a terminal outbox event. */
    OUTBOX_RETRY,

    /** A relay lease expired without a verdict and the row was returned to PENDING. */
    OUTBOX_UNLOCK,

    /** A row that had published but whose status never caught up was reconciled to PUBLISHED. */
    OUTBOX_REPUBLISH,

    /** A SCHEDULED job with no schedule event at all was given one. */
    SCHEDULED_JOB_REPAIR,

    /** A RETRYING job with no retry event at all was given one. */
    RETRY_JOB_REPAIR,

    /** Lease bookkeeping cleared from a job that had already reached a terminal status. */
    LEASE_REPAIR,

    /** A published outbox row was deleted by retention cleanup. */
    OUTBOX_CLEANUP,

    /** A STARTED effect older than the stale threshold was closed under the configured policy. */
    STALE_EFFECT_REPAIR
}
