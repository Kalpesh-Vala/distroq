package com.distroq.reliability;

/** The five things reconciliation looks at, and the grouping the admin API reports. */
public enum FindingCategory {
    OUTBOX,
    SCHEDULED_JOBS,
    RETRIES,
    EXECUTION_LEASES,
    EFFECTS
}
