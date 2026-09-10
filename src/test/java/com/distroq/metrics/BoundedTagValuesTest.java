package com.distroq.metrics;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cap that keeps a caller-chosen string from becoming an unbounded set of time series.
 */
class BoundedTagValuesTest {

    @Test
    void aKnownValueIsPassedThrough() {
        assertThat(new BoundedTagValues(10).valueFor("send-email")).isEqualTo("send-email");
    }

    @Test
    void theSameValueDoesNotConsumeTheBudgetTwice() {
        BoundedTagValues values = new BoundedTagValues(2);

        IntStream.range(0, 100).forEach(i -> values.valueFor("send-email"));
        values.valueFor("build-report");

        assertThat(values.distinctAdmitted()).isEqualTo(2);
        assertThat(values.valueFor("build-report")).isEqualTo("build-report");
    }

    @Test
    void everythingBeyondTheCapCollapsesIntoOneSeries() {
        BoundedTagValues values = new BoundedTagValues(3);

        IntStream.range(0, 1000)
                .forEach(i -> values.valueFor("order-" + UUID.randomUUID()));

        assertThat(values.distinctAdmitted()).isLessThanOrEqualTo(3);
    }

    @Test
    void anAdmittedValueKeepsItsSeriesAfterTheCapIsReached() {
        // eviction would make a series disappear and reappear with a gap, which is worse than
        // never having admitted it
        BoundedTagValues values = new BoundedTagValues(1);

        assertThat(values.valueFor("send-email")).isEqualTo("send-email");
        assertThat(values.valueFor("build-report")).isEqualTo(BoundedTagValues.OVERFLOW);
        assertThat(values.valueFor("send-email")).isEqualTo("send-email");
    }

    @Test
    void anAbsentValueIsUnknownRatherThanNullOrEmpty() {
        BoundedTagValues values = new BoundedTagValues(10);

        assertThat(values.valueFor(null)).isEqualTo(BoundedTagValues.UNKNOWN);
        assertThat(values.valueFor("   ")).isEqualTo(BoundedTagValues.UNKNOWN);
    }

    @Test
    void anOverLongValueNeverReachesTheRegistry() {
        assertThat(new BoundedTagValues(10).valueFor("x".repeat(65)))
                .isEqualTo(BoundedTagValues.OVERFLOW);
    }

    @Test
    void aValueThatWouldBreakAPrometheusExpositionLineIsRejected() {
        BoundedTagValues values = new BoundedTagValues(10);

        assertThat(values.valueFor("type\nwith-newline")).isEqualTo(BoundedTagValues.OVERFLOW);
        assertThat(values.valueFor("type\"quoted")).isEqualTo(BoundedTagValues.OVERFLOW);
        assertThat(values.valueFor("type with space")).isEqualTo(BoundedTagValues.OVERFLOW);
        assertThat(values.valueFor("type{brace}")).isEqualTo(BoundedTagValues.OVERFLOW);
    }

    @Test
    void aCapBelowOneIsTreatedAsOneRatherThanRejectingEverything() {
        BoundedTagValues values = new BoundedTagValues(0);

        assertThat(values.valueFor("send-email")).isEqualTo("send-email");
    }
}
