package com.distroq.dashboard;

import com.distroq.reliability.FindingType;

import java.util.EnumSet;
import java.util.Set;

/**
 * How urgently a reconciliation finding wants a human.
 *
 * <p>Severity is a presentation concern and lives here rather than on {@link FindingType}, which
 * is a v0.8 reliability contract the dashboard has no business editing. It is also deliberately
 * not the same axis as {@code autoRepairable}: {@code EXPIRED_OUTBOX_LOCK} is repairable and
 * routine, {@code TERMINAL_JOB_HOLDING_LEASE} is repairable and still only bookkeeping, while
 * {@code TERMINAL_OUTBOX_FAILURE} is not repairable precisely because it needs a decision.
 *
 * <p>ERROR means work has stopped or state disagrees with itself. WARNING means something is late
 * and may resolve itself. INFO means the system noticed and can tidy it up.
 */
enum FindingSeverity {

    ERROR,
    WARNING,
    INFO;

    /** State that is already wrong, or work that will not proceed without a decision. */
    private static final Set<FindingType> ERRORS = EnumSet.of(
            FindingType.TERMINAL_OUTBOX_FAILURE,
            FindingType.MALFORMED_OUTBOX_PAYLOAD,
            FindingType.ORPHANED_OUTBOX_EVENT,
            FindingType.SCHEDULED_JOB_EVENT_FAILED,
            FindingType.RETRY_EVENT_FAILED,
            FindingType.DUPLICATE_SCHEDULE_EVENT,
            FindingType.DUPLICATE_RETRY_EVENT,
            FindingType.MULTIPLE_IN_PROGRESS_ATTEMPTS,
            FindingType.ATTEMPT_OWNER_MISMATCH);

    /** Bookkeeping the system can correct on its own; nothing has stopped. */
    private static final Set<FindingType> INFOS = EnumSet.of(
            FindingType.EXPIRED_OUTBOX_LOCK,
            FindingType.INCONSISTENT_PUBLISHED_OUTBOX,
            FindingType.TERMINAL_JOB_HOLDING_LEASE);

    static FindingSeverity of(FindingType type) {
        if (ERRORS.contains(type)) {
            return ERROR;
        }
        return INFOS.contains(type) ? INFO : WARNING;
    }
}
