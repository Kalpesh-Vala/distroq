package com.distroq.dashboard;

import com.distroq.reliability.FindingType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Severity is a presentation axis, and deliberately not the {@code autoRepairable} one.
 *
 * <p>The two disagree on purpose. {@code EXPIRED_OUTBOX_LOCK} is repairable and routine;
 * {@code TERMINAL_OUTBOX_FAILURE} is not repairable precisely because it is a decision rather than
 * a defect. Sorting a findings table by "can the machine fix it" would bury the ones that need a
 * person.
 */
class FindingSeverityTest {

    @ParameterizedTest
    @EnumSource(FindingType.class)
    void everyFindingTypeHasASeverity(FindingType type) {
        assertThat(FindingSeverity.of(type)).isNotNull();
    }

    @Test
    void stateThatIsAlreadyWrongIsAnError() {
        assertThat(FindingSeverity.of(FindingType.TERMINAL_OUTBOX_FAILURE))
                .isEqualTo(FindingSeverity.ERROR);
        assertThat(FindingSeverity.of(FindingType.ATTEMPT_OWNER_MISMATCH))
                .isEqualTo(FindingSeverity.ERROR);
        assertThat(FindingSeverity.of(FindingType.MULTIPLE_IN_PROGRESS_ATTEMPTS))
                .isEqualTo(FindingSeverity.ERROR);
    }

    @Test
    void bookkeepingTheSystemCanCorrectIsInformational() {
        assertThat(FindingSeverity.of(FindingType.EXPIRED_OUTBOX_LOCK))
                .isEqualTo(FindingSeverity.INFO);
        assertThat(FindingSeverity.of(FindingType.TERMINAL_JOB_HOLDING_LEASE))
                .isEqualTo(FindingSeverity.INFO);
    }

    @Test
    void somethingMerelyLateIsAWarning() {
        assertThat(FindingSeverity.of(FindingType.STALE_PENDING_OUTBOX))
                .isEqualTo(FindingSeverity.WARNING);
        assertThat(FindingSeverity.of(FindingType.EXPIRED_EXECUTION_LEASE))
                .isEqualTo(FindingSeverity.WARNING);
    }

    @Test
    void severityIsNotTheRepairabilityAxis() {
        // repairable and routine
        assertThat(FindingType.EXPIRED_OUTBOX_LOCK.autoRepairable()).isTrue();
        assertThat(FindingSeverity.of(FindingType.EXPIRED_OUTBOX_LOCK))
                .isEqualTo(FindingSeverity.INFO);

        // not repairable, and that is exactly why it needs a person
        assertThat(FindingType.TERMINAL_OUTBOX_FAILURE.autoRepairable()).isFalse();
        assertThat(FindingSeverity.of(FindingType.TERMINAL_OUTBOX_FAILURE))
                .isEqualTo(FindingSeverity.ERROR);
    }
}
