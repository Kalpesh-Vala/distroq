package com.distroq.dashboard;

/**
 * What the dashboard is allowed to show of a string it did not write itself.
 *
 * <p>The dashboard renders three kinds of text that originate outside this application's control:
 * an outbox relay error, a job error message, and an effect error. None of them is a payload, but
 * all of them can quote one — an HTTP client exception happily includes the response body it
 * failed on, and that body is the customer's data.
 *
 * <p>So: truncated to a length that fits a table cell, control characters removed so a value
 * cannot inject line breaks into a log or terminal, and marked as truncated when it was. The full
 * value stays where it already is, in the database and in the administrative API, behind the same
 * token — this only decides what a wall display shows.
 */
final class Redaction {

    static final int MAX_ERROR_LENGTH = 240;
    static final String TRUNCATION_MARKER = "… (truncated)";

    private Redaction() {
    }

    static String error(String raw) {
        if (raw == null) {
            return null;
        }
        StringBuilder cleaned = new StringBuilder(Math.min(raw.length(), MAX_ERROR_LENGTH));
        for (int i = 0; i < raw.length() && cleaned.length() < MAX_ERROR_LENGTH; i++) {
            char c = raw.charAt(i);
            cleaned.append(Character.isISOControl(c) ? ' ' : c);
        }
        String result = cleaned.toString().trim();
        return raw.length() > MAX_ERROR_LENGTH ? result + TRUNCATION_MARKER : result;
    }
}
