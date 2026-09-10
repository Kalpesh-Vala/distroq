package com.distroq.api.error;

import com.distroq.observability.LogFields;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The response body every failure produces.
 *
 * <p>Two properties matter more than the rest and are asserted repeatedly: the {@code code} is
 * stable and machine-readable, and nothing internal leaks into {@code message}. A caller writing
 * an integration against this API should never have to match on prose, and an attacker probing it
 * should never learn the schema.
 */
class ErrorContractTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void aCodedFailureKeepsItsCodeAndStatus() {
        ResponseEntity<ApiError> response = handler.handleApi(
                new ApiException(ErrorCode.INVALID_SCHEDULED_AT, "scheduledAt must be ISO-8601"),
                request("/api/jobs"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("INVALID_SCHEDULED_AT");
        assertThat(response.getBody().status()).isEqualTo(400);
        assertThat(response.getBody().error()).isEqualTo("Bad Request");
        assertThat(response.getBody().path()).isEqualTo("/api/jobs");
        assertThat(response.getBody().message()).isEqualTo("scheduledAt must be ISO-8601");
        assertThat(response.getBody().timestamp()).isNotNull();
    }

    @Test
    void everyErrorCodeAgreesWithItsStatus() {
        for (ErrorCode code : ErrorCode.values()) {
            ResponseEntity<ApiError> response = handler.handleApi(
                    new ApiException(code, "any"), request("/api/jobs"));

            assertThat(response.getStatusCode().value())
                    .as("status for %s", code)
                    .isEqualTo(response.getBody().status());
        }
    }

    @Test
    void theCorrelationIdIsCarriedIntoTheBody() {
        MDC.put(LogFields.CORRELATION_ID, "abc-123");

        ResponseEntity<ApiError> response = handler.handleApi(
                new ApiException(ErrorCode.JOB_NOT_FOUND, "gone"), request("/api/jobs/1"));

        assertThat(response.getBody().correlationId()).isEqualTo("abc-123");
    }

    @Test
    void anUncodedResponseStatusExceptionStillGetsACodeFromItsStatus() {
        ResponseEntity<ApiError> response = handler.handleStatus(
                new ResponseStatusException(HttpStatus.CONFLICT, "already running"),
                request("/api/jobs/1/retry"));

        assertThat(response.getBody().code()).isEqualTo("CONFLICT");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void anUnexpectedFailureDoesNotLeakItsMessageOrItsType() {
        ResponseEntity<ApiError> response = handler.handleUnexpected(
                new IllegalStateException(
                        "could not execute statement [insert into jobs ...]; password=hunter2"),
                request("/api/jobs"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().code()).isEqualTo("INTERNAL_ERROR");
        assertThat(response.getBody().message())
                .isEqualTo("The request could not be completed")
                .doesNotContain("insert into")
                .doesNotContain("hunter2")
                .doesNotContain("IllegalStateException");
    }

    @Test
    void anUnreachableDependencyIsA503AndNamesNothing() {
        ResponseEntity<ApiError> response = handler.handleDependencyDown(
                new DataAccessResourceFailureException(
                        "Connection to jdbc:postgresql://db:5432/distroq?password=hunter2 refused"),
                request("/api/jobs"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().code()).isEqualTo("DEPENDENCY_UNAVAILABLE");
        assertThat(response.getBody().message())
                .doesNotContain("jdbc")
                .doesNotContain("hunter2");
    }

    @Test
    void anUnreadableBodyIsDescribedWithoutQuotingIt() {
        ResponseEntity<ApiError> response =
                handler.handleUnreadableBody(request("/api/jobs"));

        assertThat(response.getBody().code()).isEqualTo("INVALID_REQUEST_BODY");
        assertThat(response.getBody().message()).isEqualTo("The request body could not be read as JSON");
    }

    @Test
    void anUnknownPathIsA404WithACode() {
        ResponseEntity<ApiError> response = handler.handleUnknownPath(request("/api/nope"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().code()).isEqualTo("NOT_FOUND");
    }

    @Test
    void theBodyHasExactlyTheSevenDocumentedFieldsAndNoTrace() {
        ApiError body = ApiErrors.body(ErrorCode.VALIDATION_FAILED, "bad", "/api/jobs");

        assertThat(ApiError.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("timestamp", "status", "error", "code", "message", "path",
                        "correlationId");
        assertThat(body.code()).isEqualTo("VALIDATION_FAILED");
    }

    private static MockHttpServletRequest request(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRequestURI(path);
        return request;
    }
}
