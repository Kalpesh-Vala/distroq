package com.distroq.api.error;

import org.springframework.web.server.ResponseStatusException;

/**
 * A {@link ResponseStatusException} that also carries a stable {@link ErrorCode}.
 *
 * <p>Extending rather than replacing is deliberate. Every rejection since v0.4 has been a
 * {@code ResponseStatusException}, callers and tests match on that type, and Spring MVC already
 * knows how to turn one into a status. v1.0 adds the code to the same object rather than
 * introducing a second exception hierarchy that half the code base would keep missing.
 */
public class ApiException extends ResponseStatusException {

    private final transient ErrorCode code;

    public ApiException(ErrorCode code, String reason) {
        super(code.status(), reason);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }

    public static ApiException of(ErrorCode code, String reason) {
        return new ApiException(code, reason);
    }
}
