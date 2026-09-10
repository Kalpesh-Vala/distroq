package com.distroq.api.error;

import com.distroq.observability.LogContext;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestValueException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * Turns every failure into the same body.
 *
 * <p>Two rules decide what a caller is told. Anything the caller could have avoided — a bad
 * priority, a missing header, a reused idempotency key — is described precisely, because vagueness
 * there only costs a support ticket. Anything the caller could not have avoided is described
 * generically, because the details are the schema, the SQL, the Redis command or the connection
 * string, and the operator can find all of them in the log line that carries the same
 * {@code correlationId}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException failure, HttpServletRequest request) {
        return respond(failure.code(), reasonOf(failure), request, failure.getHeaders());
    }

    /**
     * Everything raised before v1.0 gave codes to individual call sites. The status still decides
     * what the caller sees; only the code is generic.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> handleStatus(ResponseStatusException failure,
                                                 HttpServletRequest request) {
        return respond(ErrorCode.forStatus(failure.getStatusCode().value()), reasonOf(failure),
                request, failure.getHeaders());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleInvalidBody(MethodArgumentNotValidException failure,
                                                      HttpServletRequest request) {
        String detail = failure.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return respond(ErrorCode.VALIDATION_FAILED,
                detail.isEmpty() ? "The request body failed validation" : detail, request, null);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(HttpServletRequest request) {
        // the parser's own message quotes the offending bytes, which may be the payload
        return respond(ErrorCode.INVALID_REQUEST_BODY,
                "The request body could not be read as JSON", request, null);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleBadParameter(MethodArgumentTypeMismatchException failure,
                                                       HttpServletRequest request) {
        String expected = failure.getRequiredType() == null
                ? "the expected type" : failure.getRequiredType().getSimpleName();
        return respond(ErrorCode.INVALID_PARAMETER,
                "'" + failure.getName() + "' could not be read as " + expected, request, null);
    }

    @ExceptionHandler(MissingRequestValueException.class)
    public ResponseEntity<ApiError> handleMissingValue(MissingRequestValueException failure,
                                                       HttpServletRequest request) {
        return respond(ErrorCode.VALIDATION_FAILED, failure.getMessage(), request, null);
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiError> handleUnknownPath(HttpServletRequest request) {
        return respond(ErrorCode.NOT_FOUND, "No endpoint " + request.getMethod() + " "
                + request.getRequestURI(), request, null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleBadMethod(HttpRequestMethodNotSupportedException failure,
                                                    HttpServletRequest request) {
        return respond(ErrorCode.METHOD_NOT_ALLOWED,
                request.getMethod() + " is not supported by " + request.getRequestURI(), request,
                failure.getHeaders());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> handleBadMediaType(HttpServletRequest request) {
        return respond(ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                "The request content type is not supported", request, null);
    }

    /**
     * PostgreSQL or Redis is unreachable. 503 rather than 500: the request was well formed and
     * repeating it later is the correct thing for the caller to do.
     */
    @ExceptionHandler({DataAccessResourceFailureException.class,
            CannotCreateTransactionException.class,
            org.springframework.data.redis.RedisConnectionFailureException.class})
    public ResponseEntity<ApiError> handleDependencyDown(Exception failure,
                                                         HttpServletRequest request) {
        try (LogContext ignored = LogContext.empty().errorType(failure)) {
            log.error("A dependency was unavailable while serving {} {}", request.getMethod(),
                    request.getRequestURI(), failure);
        }
        return respond(ErrorCode.DEPENDENCY_UNAVAILABLE,
                "A required dependency is unavailable; retry later", request, null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception failure,
                                                     HttpServletRequest request) {
        try (LogContext ignored = LogContext.empty().errorType(failure)) {
            log.error("Unhandled failure serving {} {}", request.getMethod(),
                    request.getRequestURI(), failure);
        }
        return respond(ErrorCode.INTERNAL_ERROR, "The request could not be completed", request,
                null);
    }

    private static String reasonOf(ResponseStatusException failure) {
        return failure.getReason() == null
                ? failure.getStatusCode().toString() : failure.getReason();
    }

    private static ResponseEntity<ApiError> respond(ErrorCode code, String message,
                                                    HttpServletRequest request,
                                                    HttpHeaders headers) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(code.status());
        if (headers != null && !headers.isEmpty()) {
            builder.headers(headers);
        }
        return builder.body(ApiErrors.body(code, message, request.getRequestURI()));
    }
}
