package com.distroq.observability;

import java.util.List;

/**
 * The MDC keys that become top-level fields in a production log line.
 *
 * <p>Only identifiers, enumerations and durations appear here. Payloads, error bodies, admin
 * tokens and raw idempotency keys deliberately do not: a field that is safe on one log line and
 * sensitive on another is a field that will eventually be logged in the wrong place, so the safe
 * set is fixed once, here, rather than decided per call site.
 */
public final class LogFields {

    public static final String EVENT = "event";
    public static final String CORRELATION_ID = "correlationId";
    public static final String WORKER_ID = "workerId";
    public static final String CONSUMER_NAME = "consumerName";
    public static final String JOB_ID = "jobId";
    public static final String ATTEMPT_ID = "attemptId";
    public static final String STREAM = "stream";
    public static final String STREAM_ENTRY_ID = "streamEntryId";
    public static final String OUTBOX_EVENT_ID = "outboxEventId";
    public static final String EVENT_TYPE = "eventType";
    public static final String PRIORITY = "priority";
    public static final String STATUS = "status";
    public static final String DURATION_MS = "durationMs";
    public static final String ERROR_TYPE = "errorType";

    /** The order fields are emitted in, so two log lines for the same event read alike. */
    public static final List<String> ORDERED = List.of(
            EVENT, CORRELATION_ID, WORKER_ID, CONSUMER_NAME, JOB_ID, ATTEMPT_ID, STREAM,
            STREAM_ENTRY_ID, OUTBOX_EVENT_ID, EVENT_TYPE, PRIORITY, STATUS, DURATION_MS,
            ERROR_TYPE);

    private LogFields() {
    }
}
