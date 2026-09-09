package com.distroq.reliability;

/** What reconciliation did about a finding on this run. */
public enum Resolution {

    /** Seen and named. The default, and the only outcome possible in preview mode. */
    REPORTED,

    /** Repaired, with a matching {@code reliability_actions} row committed alongside. */
    REPAIRED,

    /** Repair was permitted this run but not for this finding type, or not for this row. */
    SKIPPED,

    /** Repair was attempted and threw. The finding stays unresolved. */
    FAILED
}
