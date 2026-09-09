package com.distroq.reliability;

/**
 * Every inconsistency v0.8 knows how to name, and whether it may be repaired without a human.
 *
 * <p>{@code autoRepairable} is the safety boundary, written once here rather than re-decided at
 * each call site. A finding is auto-repairable only when the database state <em>proves</em> the
 * repair is correct — not when the repair merely seems likely to help. Everything ambiguous is
 * reported and left alone, which is why the majority of this list is false.
 */
public enum FindingType {

    /** PENDING for longer than the threshold. The relay owns it; repairing it here would race. */
    STALE_PENDING_OUTBOX(FindingCategory.OUTBOX, false),

    /** A relay lease expired without a verdict. Safe: the lease is the proof nobody owns it. */
    EXPIRED_OUTBOX_LOCK(FindingCategory.OUTBOX, true),

    /** Terminal. An operator decides whether the world is ready for a republication. */
    TERMINAL_OUTBOX_FAILURE(FindingCategory.OUTBOX, false),

    /** Published but not PUBLISHED. Safe: {@code publishedAt} is the proof it published. */
    INCONSISTENT_PUBLISHED_OUTBOX(FindingCategory.OUTBOX, true),

    /** The aggregate job is gone. Deleting the event would delete the only record of why. */
    ORPHANED_OUTBOX_EVENT(FindingCategory.OUTBOX, false),

    /** The payload does not parse, or its identity disagrees with the row. */
    MALFORMED_OUTBOX_PAYLOAD(FindingCategory.OUTBOX, false),

    STALE_SCHEDULED_JOB(FindingCategory.SCHEDULED_JOBS, false),

    /** No schedule event exists at all, so no publication can exist. Safe to create one. */
    SCHEDULED_JOB_WITHOUT_EVENT(FindingCategory.SCHEDULED_JOBS, true),

    /** The schedule event is terminal. Re-arming it is the operator retry, not a repair. */
    SCHEDULED_JOB_EVENT_FAILED(FindingCategory.SCHEDULED_JOBS, false),

    /** More than one live schedule event. Choosing between them is a judgement call. */
    DUPLICATE_SCHEDULE_EVENT(FindingCategory.SCHEDULED_JOBS, false),

    /** Due in Redis and still not promoted. The promoter has stopped; nothing here can fix that. */
    UNPROMOTED_SCHEDULED_MEMBER(FindingCategory.SCHEDULED_JOBS, false),

    STALE_RETRY_JOB(FindingCategory.RETRIES, false),

    /** No retry event exists at all for this attempt. Safe to create one. */
    RETRY_JOB_WITHOUT_EVENT(FindingCategory.RETRIES, true),

    RETRY_EVENT_FAILED(FindingCategory.RETRIES, false),

    DUPLICATE_RETRY_EVENT(FindingCategory.RETRIES, false),

    /**
     * A RUNNING job whose lease ran out. Never repaired automatically: the lease expiring proves
     * the worker stopped renewing, not that it stopped working, and the recovery path
     * ({@code XAUTOCLAIM} plus a fresh database claim) already handles the real case.
     */
    EXPIRED_EXECUTION_LEASE(FindingCategory.EXECUTION_LEASES, false),

    MISSING_ACTIVE_ATTEMPT(FindingCategory.EXECUTION_LEASES, false),

    MULTIPLE_IN_PROGRESS_ATTEMPTS(FindingCategory.EXECUTION_LEASES, false),

    ATTEMPT_OWNER_MISMATCH(FindingCategory.EXECUTION_LEASES, false),

    /** Finished, but still holding lease columns. Safe: a terminal job has no live owner. */
    TERMINAL_JOB_HOLDING_LEASE(FindingCategory.EXECUTION_LEASES, true),

    /**
     * STARTED past the stale threshold. Auto-repairable only in the narrow sense that the
     * configured policy may close the row; it can never establish whether the effect happened.
     */
    STALE_STARTED_EFFECT(FindingCategory.EFFECTS, true);

    private final FindingCategory category;
    private final boolean autoRepairable;

    FindingType(FindingCategory category, boolean autoRepairable) {
        this.category = category;
        this.autoRepairable = autoRepairable;
    }

    public FindingCategory category() {
        return category;
    }

    public boolean autoRepairable() {
        return autoRepairable;
    }
}
