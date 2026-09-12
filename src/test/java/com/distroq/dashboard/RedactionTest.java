package com.distroq.dashboard;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a dashboard is allowed to render of text it did not write.
 *
 * <p>An outbox {@code lastError} is written by the relay, but the relay wrote it from an exception,
 * and an HTTP client exception quotes the response body it failed on. That body is a customer's
 * data, and this page is on a wall display.
 */
class RedactionTest {

    @Test
    void aShortErrorSurvivesUnchanged() {
        assertThat(Redaction.error("Connection refused")).isEqualTo("Connection refused");
    }

    @Test
    void nullStaysNullRatherThanBecomingAnEmptyCell() {
        assertThat(Redaction.error(null)).isNull();
    }

    @Test
    void aLongErrorIsTruncatedAndSaysSo() {
        String raw = "x".repeat(Redaction.MAX_ERROR_LENGTH + 500);

        String redacted = Redaction.error(raw);

        assertThat(redacted).hasSize(Redaction.MAX_ERROR_LENGTH
                + Redaction.TRUNCATION_MARKER.length());
        assertThat(redacted).endsWith(Redaction.TRUNCATION_MARKER);
    }

    @Test
    void controlCharactersCannotInjectLinesIntoALogOrATerminal() {
        String redacted = Redaction.error("failed\n\r\u0000ERROR: fake log line\u001b[31m");

        assertThat(redacted).doesNotContain("\n").doesNotContain("\r")
                .doesNotContain("\u0000").doesNotContain("\u001b");
        assertThat(redacted).contains("failed").contains("fake log line");
    }

    @Test
    void aBoundaryLengthErrorIsNotMarkedTruncated() {
        String exact = "y".repeat(Redaction.MAX_ERROR_LENGTH);

        assertThat(Redaction.error(exact)).isEqualTo(exact);
    }
}
