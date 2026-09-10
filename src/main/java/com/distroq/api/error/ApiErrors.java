package com.distroq.api.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.distroq.observability.LogFields;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;

/**
 * Builds the standard error body, and writes it straight to the response for the callers that
 * never reach Spring MVC.
 *
 * <p>The admin authentication filter rejects a request before any handler is chosen, so
 * {@code @RestControllerAdvice} never sees it. Without this, a 401 would come back as the servlet
 * container's default HTML page — a different shape from every other error the API produces, and
 * one that leaks the container's name.
 */
@Component
public class ApiErrors {

    private final ObjectMapper objectMapper;

    public ApiErrors(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public static ApiError body(ErrorCode code, String message, String path) {
        return new ApiError(Instant.now(), code.status().value(), code.status().getReasonPhrase(),
                code.name(), message, path, MDC.get(LogFields.CORRELATION_ID));
    }

    public void write(HttpServletRequest request, HttpServletResponse response, ErrorCode code,
                      String message) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.reset();
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        if (code == ErrorCode.UNAUTHORIZED) {
            // RFC 9110: a 401 must say how to authenticate. The realm carries no secret.
            response.setHeader("WWW-Authenticate", "Bearer realm=\"distroq-admin\"");
        }
        objectMapper.writeValue(response.getOutputStream(),
                body(code, message, request.getRequestURI()));
        response.flushBuffer();
    }
}
