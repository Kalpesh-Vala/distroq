package com.distroq.api.error;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * The one error body every endpoint returns.
 *
 * <p>Seven fields and nothing else. There is no {@code trace}, no {@code exception} and no
 * {@code errors} array holding framework internals, because everything a caller needs to act on
 * is in {@code code} and everything an operator needs to find the log line is in
 * {@code correlationId}. A stack trace in a response body tells an attacker the framework
 * versions, the package layout and often the SQL; it tells the caller nothing they can use.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String code,
        String message,
        String path,
        String correlationId) {
}
