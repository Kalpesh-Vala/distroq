package com.distroq.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The one place a timezone can enter the system, and therefore the one place it has to be shut
 * out. Everything below is either "this offset is applied and forgotten" or "this value could
 * only be read by guessing a zone, so it is refused".
 */
class ScheduledAtParserTest {

    /** 2026-09-07T15:30:00Z, written three ways in the tests below. */
    private static final Instant TARGET = Instant.parse("2026-09-07T15:30:00Z");

    @Test
    void anAbsentValueMeansImmediateRatherThanInvalid() {
        assertThat(ScheduledAtParser.parse(null)).isNull();
    }

    @Test
    void aUtcTimestampParsesToThatInstant() {
        assertThat(ScheduledAtParser.parse("2026-09-07T15:30:00Z")).isEqualTo(TARGET);
    }

    @Test
    void aPositiveOffsetIsNormalisedToTheSameInstant() {
        assertThat(ScheduledAtParser.parse("2026-09-07T17:30:00+02:00")).isEqualTo(TARGET);
    }

    @Test
    void aNegativeOffsetIsNormalisedToTheSameInstant() {
        assertThat(ScheduledAtParser.parse("2026-09-07T10:30:00-05:00")).isEqualTo(TARGET);
    }

    @Test
    void allThreeSpellingsOfTheSameInstantAreIndistinguishableAfterParsing() {
        // this is the whole point of storing an Instant: the offset is information about how the
        // request was written, not about when the job should run
        Instant utc = ScheduledAtParser.parse("2026-09-07T15:30:00Z");
        Instant berlin = ScheduledAtParser.parse("2026-09-07T17:30:00+02:00");
        Instant newYork = ScheduledAtParser.parse("2026-09-07T10:30:00-05:00");

        assertThat(utc).isEqualTo(berlin).isEqualTo(newYork);
        assertThat(utc.toEpochMilli()).isEqualTo(berlin.toEpochMilli()).isEqualTo(newYork.toEpochMilli());
    }

    @Test
    void fractionalSecondsAreAcceptedAndKept() {
        assertThat(ScheduledAtParser.parse("2026-09-07T15:30:00.250Z"))
                .isEqualTo(Instant.parse("2026-09-07T15:30:00.250Z"));
    }

    @Test
    void surroundingWhitespaceIsTrimmedRatherThanRejected() {
        assertThat(ScheduledAtParser.parse("  2026-09-07T15:30:00Z  ")).isEqualTo(TARGET);
    }

    @Test
    void aBlankValueIsRejected() {
        // omitting the field already means "now", so an empty string cannot mean that too
        assertBadRequest("");
        assertBadRequest("   ");
    }

    @Test
    void aTimestampWithNoOffsetIsRejectedRatherThanReadInTheServerZone() {
        assertBadRequest("2026-09-07T15:30:00");
    }

    @Test
    void aSpaceSeparatedTimestampIsRejected() {
        assertBadRequest("2026-09-07 15:30:00");
    }

    @Test
    void aDateWithNoTimeIsRejected() {
        assertBadRequest("2026-09-07");
    }

    @Test
    void nonsenseIsRejected() {
        assertBadRequest("not-a-date");
    }

    @Test
    void anEpochMillisecondNumberIsRejected() {
        // a plausible thing for a client to send, and silently accepting it would mean two
        // incompatible wire formats for one field
        assertBadRequest("1788715215167");
    }

    @Test
    void everyRejectionNamesTheFieldItIsAbout() {
        for (String invalid : new String[]{"", "not-a-date", "2026-09-07 15:30:00", "2026-09-07T15:30:00"}) {
            assertThatThrownBy(() -> ScheduledAtParser.parse(invalid))
                    .as(invalid)
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("scheduledAt");
        }
    }

    @Test
    void aRejectionNamesAnAcceptableFormat() {
        assertThatThrownBy(() -> ScheduledAtParser.parse("2026-09-07T15:30:00"))
                .hasMessageContaining("2026-09-07T15:30:00Z");
    }

    @Test
    void aTimestampInThePastParsesJustLikeAnyOther() {
        // "already due" is a submission decision, not a parsing one
        assertThatCode(() -> ScheduledAtParser.parse("1999-12-31T23:59:59Z")).doesNotThrowAnyException();
        assertThat(ScheduledAtParser.parse("1999-12-31T23:59:59Z"))
                .isEqualTo(Instant.parse("1999-12-31T23:59:59Z"));
    }

    private static void assertBadRequest(String raw) {
        assertThatThrownBy(() -> ScheduledAtParser.parse(raw))
                .as(raw)
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
