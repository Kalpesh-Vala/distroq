package com.distroq.dashboard;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading a stream entry's age out of its ID.
 *
 * <p>A Redis stream entry ID is {@code <millisecondsSinceEpoch>-<sequence>}, so the age of the
 * oldest pending entry is available without fetching the entry — and therefore without reading any
 * payload field. That is the only reason the dashboard can show "oldest pending entry: 4m" at all.
 */
class QueueInspectorTest {

    @Test
    void anEntryIdCarriesItsOwnTimestamp() {
        long tenSecondsAgo = Instant.now().toEpochMilli() - 10_000;

        Long age = QueueInspector.ageOfEntry(tenSecondsAgo + "-0");

        assertThat(age).isNotNull();
        assertThat(age).isBetween(9_000L, 11_000L);
    }

    @Test
    void anIdWithoutASequenceStillParses() {
        long now = Instant.now().toEpochMilli();

        assertThat(QueueInspector.ageOfEntry(String.valueOf(now))).isNotNull();
    }

    @Test
    void aFutureIdIsClampedToZeroRatherThanReportedAsNegativeAge() {
        long ahead = Instant.now().toEpochMilli() + 60_000;

        assertThat(QueueInspector.ageOfEntry(ahead + "-3")).isZero();
    }

    @Test
    void anUnreadableIdIsNullRatherThanZero() {
        // null renders as "no data"; zero would render as "brand new", which would be a lie
        assertThat(QueueInspector.ageOfEntry(null)).isNull();
        assertThat(QueueInspector.ageOfEntry("not-an-id")).isNull();
        assertThat(QueueInspector.ageOfEntry("")).isNull();
    }
}
