package com.distroq.dashboard;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * One panel's worth of data, plus whether it is there.
 *
 * <p>The wrapper exists for a single reason: an unavailable panel must not be indistinguishable
 * from an empty one. A Redis outage that renders "pending entries: 0" is worse than one that
 * renders nothing, because zero is a number an operator will act on. {@code availability} is
 * therefore part of every section rather than an error the caller has to infer from a null.
 *
 * <p>{@code reason} is an exception class name or a short structural sentence. It is never an
 * exception message: a Lettuce or Hikari failure quotes the connection string, and the connection
 * string carries the password.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Section<T>(String availability, String reason, Instant lastUpdatedAt, T data) {

    public static final String AVAILABLE = "AVAILABLE";
    public static final String UNAVAILABLE = "UNAVAILABLE";
    public static final String NOT_CONFIGURED = "NOT_CONFIGURED";

    public static <T> Section<T> available(T data) {
        return new Section<>(AVAILABLE, null, Instant.now(), data);
    }

    public static <T> Section<T> available(T data, Instant lastUpdatedAt) {
        return new Section<>(AVAILABLE, null, lastUpdatedAt, data);
    }

    /** Only the class name of the failure travels; see the class comment. */
    public static <T> Section<T> unavailable(Throwable failure) {
        return new Section<>(UNAVAILABLE,
                failure == null ? "unknown" : failure.getClass().getSimpleName(),
                Instant.now(), null);
    }

    public static <T> Section<T> notConfigured(String reason) {
        return new Section<>(NOT_CONFIGURED, reason, Instant.now(), null);
    }

    public boolean isAvailable() {
        return AVAILABLE.equals(availability);
    }
}
