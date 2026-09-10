package com.distroq.observability;

import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Scoped MDC fields for one log statement or one block of them.
 *
 * <p>Restores whatever was there before rather than clearing, because a worker thread already
 * carries a {@code workerId} and a request thread already carries a {@code correlationId}, and a
 * nested block that cleared the MDC on exit would silently strip them from every later line on
 * that thread.
 *
 * <p>Null values are dropped rather than written as the string "null": an absent field is
 * absent from the JSON, which is what a log query expects.
 */
public final class LogContext implements AutoCloseable {

    private final Map<String, String> previous = new LinkedHashMap<>();

    private LogContext() {
    }

    public static LogContext event(String eventName) {
        return new LogContext().put(LogFields.EVENT, eventName);
    }

    public static LogContext empty() {
        return new LogContext();
    }

    public LogContext put(String key, Object value) {
        if (value == null) {
            return this;
        }
        String rendered = String.valueOf(value);
        if (rendered.isEmpty()) {
            return this;
        }
        if (!previous.containsKey(key)) {
            previous.put(key, MDC.get(key));
        }
        MDC.put(key, rendered);
        return this;
    }

    public LogContext job(Object jobId) {
        return put(LogFields.JOB_ID, jobId);
    }

    public LogContext attempt(Object attemptId) {
        return put(LogFields.ATTEMPT_ID, attemptId);
    }

    public LogContext worker(String workerId) {
        return put(LogFields.WORKER_ID, workerId);
    }

    public LogContext consumer(String consumerName) {
        return put(LogFields.CONSUMER_NAME, consumerName);
    }

    public LogContext stream(String streamKey, String entryId) {
        return put(LogFields.STREAM, streamKey).put(LogFields.STREAM_ENTRY_ID, entryId);
    }

    public LogContext outboxEvent(Object eventId) {
        return put(LogFields.OUTBOX_EVENT_ID, eventId);
    }

    public LogContext eventType(Object eventType) {
        return put(LogFields.EVENT_TYPE, eventType);
    }

    public LogContext priority(Object priority) {
        return put(LogFields.PRIORITY, priority);
    }

    public LogContext status(Object status) {
        return put(LogFields.STATUS, status);
    }

    public LogContext durationMs(long durationMs) {
        return put(LogFields.DURATION_MS, durationMs);
    }

    /** The exception's class name only. Its message may quote a payload or a credential. */
    public LogContext errorType(Throwable failure) {
        return failure == null ? this : put(LogFields.ERROR_TYPE, failure.getClass().getName());
    }

    public LogContext errorType(String errorType) {
        return put(LogFields.ERROR_TYPE, errorType);
    }

    @Override
    public void close() {
        previous.forEach((key, value) -> {
            if (value == null) {
                MDC.remove(key);
            } else {
                MDC.put(key, value);
            }
        });
        previous.clear();
    }
}
