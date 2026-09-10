package com.distroq.observability;

/**
 * The stable names of the transitions worth alerting on.
 *
 * <p>These are a contract. A log line's wording may change between releases; the value of the
 * {@code event} field may not, because dashboards and alert rules match on it. Anything not in
 * this list is a narrative log line and must not be depended on.
 */
public final class Events {

    public static final String JOB_SUBMITTED = "job.submitted";
    public static final String JOB_SCHEDULED = "job.scheduled";
    public static final String JOB_STARTED = "job.started";
    public static final String JOB_SUCCEEDED = "job.succeeded";
    public static final String JOB_RETRY_SCHEDULED = "job.retry_scheduled";
    public static final String JOB_DEAD_LETTERED = "job.dead_lettered";
    public static final String JOB_REPLAYED = "job.replayed";
    public static final String JOB_RECLAIMED = "job.reclaimed";
    public static final String JOB_EXECUTION_CLAIMED = "job.execution_claimed";
    public static final String JOB_EXECUTION_LEASE_LOST = "job.execution_lease_lost";

    public static final String OUTBOX_PUBLISHED = "outbox.published";
    public static final String OUTBOX_FAILED = "outbox.failed";
    public static final String OUTBOX_OPERATOR_RETRY = "outbox.operator_retry";

    public static final String RECONCILIATION_FINDING = "reconciliation.finding";
    public static final String RECONCILIATION_REPAIR = "reconciliation.repair";

    public static final String APPLICATION_READINESS_CHANGED = "application.readiness_changed";
    public static final String APPLICATION_SHUTDOWN_STARTED = "application.shutdown_started";
    public static final String APPLICATION_SHUTDOWN_COMPLETED = "application.shutdown_completed";

    public static final String ADMIN_AUTHENTICATION_FAILED = "admin.authentication_failed";

    private Events() {
    }
}
