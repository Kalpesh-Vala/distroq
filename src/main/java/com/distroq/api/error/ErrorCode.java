package com.distroq.api.error;

import org.springframework.http.HttpStatus;

/**
 * The machine-readable half of an error response.
 *
 * <p>The HTTP status says what kind of thing went wrong; the code says which thing. A client that
 * needs to tell "this idempotency key was reused for a different request" apart from "this job is
 * not dead-lettered" cannot do it from {@code 409} and should not have to do it by matching on a
 * human sentence that is free to be reworded.
 *
 * <p>These names are a public contract from v1.0 onwards. Codes may be added; an existing code may
 * not change its meaning or its status.
 */
public enum ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    INVALID_REQUEST_BODY(HttpStatus.BAD_REQUEST),
    INVALID_PARAMETER(HttpStatus.BAD_REQUEST),
    INVALID_PRIORITY(HttpStatus.BAD_REQUEST),
    INVALID_SCHEDULED_AT(HttpStatus.BAD_REQUEST),
    INVALID_MAX_ATTEMPTS(HttpStatus.BAD_REQUEST),
    INVALID_IDEMPOTENCY_KEY(HttpStatus.BAD_REQUEST),
    MISSING_ADMIN_REASON(HttpStatus.BAD_REQUEST),
    INVALID_ADMIN_REASON(HttpStatus.BAD_REQUEST),
    INVALID_ADMIN_ACTOR(HttpStatus.BAD_REQUEST),

    UNAUTHORIZED(HttpStatus.UNAUTHORIZED),
    FORBIDDEN(HttpStatus.FORBIDDEN),
    ADMIN_DISABLED(HttpStatus.FORBIDDEN),

    NOT_FOUND(HttpStatus.NOT_FOUND),
    JOB_NOT_FOUND(HttpStatus.NOT_FOUND),
    DEAD_LETTER_NOT_FOUND(HttpStatus.NOT_FOUND),
    OUTBOX_EVENT_NOT_FOUND(HttpStatus.NOT_FOUND),
    IDEMPOTENCY_KEY_NOT_FOUND(HttpStatus.NOT_FOUND),

    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE),

    IDEMPOTENCY_KEY_CONFLICT(HttpStatus.CONFLICT),
    JOB_NOT_DEAD_LETTERED(HttpStatus.CONFLICT),
    OUTBOX_EVENT_NOT_FAILED(HttpStatus.CONFLICT),
    CONFLICT(HttpStatus.CONFLICT),

    DEPENDENCY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    /** The fallback for a status raised without a code, so every response still carries one. */
    public static ErrorCode forStatus(int status) {
        return switch (status) {
            case 400 -> VALIDATION_FAILED;
            case 401 -> UNAUTHORIZED;
            case 403 -> FORBIDDEN;
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 409 -> CONFLICT;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            case 503 -> DEPENDENCY_UNAVAILABLE;
            default -> INTERNAL_ERROR;
        };
    }
}
