package com.distroq.dashboard;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wrapper that keeps "unavailable" from being rendered as zero.
 *
 * <p>This is the smallest piece of v1.1 and the one the whole partial-failure story rests on. A
 * Redis outage that shows "pending entries: 0" is worse than one that shows nothing, because zero
 * is a number an operator will act on.
 */
class SectionTest {

    @Test
    void anAvailableSectionCarriesItsDataAndATimestamp() {
        Section<String> section = Section.available("value");

        assertThat(section.availability()).isEqualTo(Section.AVAILABLE);
        assertThat(section.data()).isEqualTo("value");
        assertThat(section.lastUpdatedAt()).isNotNull();
        assertThat(section.reason()).isNull();
        assertThat(section.isAvailable()).isTrue();
    }

    @Test
    void anUnavailableSectionHasNoDataAtAllRatherThanEmptyData() {
        Section<String> section = Section.unavailable(new IllegalStateException("boom"));

        assertThat(section.availability()).isEqualTo(Section.UNAVAILABLE);
        assertThat(section.data()).isNull();
        assertThat(section.isAvailable()).isFalse();
    }

    @Test
    void onlyTheExceptionTypeTravelsBecauseAMessageCanQuoteAConnectionString() {
        Section<String> section = Section.unavailable(new IllegalStateException(
                "Unable to connect to redis://:hunter2@cache.internal:6379"));

        assertThat(section.reason()).isEqualTo("IllegalStateException");
        assertThat(section.toString()).doesNotContain("hunter2").doesNotContain("redis://");
    }

    @Test
    void notConfiguredIsItsOwnAnswer() {
        // "analytics has never been exported here" is not a failure and must not read as one
        Section<String> section = Section.notConfigured("no export");

        assertThat(section.availability()).isEqualTo(Section.NOT_CONFIGURED);
        assertThat(section.isAvailable()).isFalse();
        assertThat(section.data()).isNull();
    }

    @Test
    void aPanelThatThrowsBecomesAnUnavailableSectionRatherThanAFailedRequest() {
        Section<String> section = Sections.read("queues", () -> {
            throw new IllegalStateException("redis://:hunter2@cache:6379 is unreachable");
        });

        assertThat(section.availability()).isEqualTo(Section.UNAVAILABLE);
        assertThat(section.reason()).isEqualTo("IllegalStateException");
        assertThat(section.data()).isNull();
    }

    @Test
    void aPanelThatSucceedsIsPassedStraightThrough() {
        assertThat(Sections.read("queues", () -> 42).data()).isEqualTo(42);
    }
}
