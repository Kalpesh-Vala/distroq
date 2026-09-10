package com.distroq.observability;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The identifier that ties a request, its log lines and its error body together.
 *
 * <p>The header is attacker-controlled and ends up in a log file, so the sanitiser is the security
 * boundary here: a value containing a newline could forge a whole log record, and a value
 * containing JSON punctuation could break the structured line it is embedded in.
 */
class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void aSuppliedIdIsHonouredWhenItIsSafe() {
        assertThat(CorrelationIdFilter.sanitize("trace-0123_ABC.9")).isEqualTo("trace-0123_ABC.9");
    }

    @Test
    void anIdThatCouldForgeALogRecordIsReplaced() {
        assertThat(CorrelationIdFilter.sanitize("a\nlevel=ERROR")).doesNotContain("\n");
        assertThat(CorrelationIdFilter.sanitize("{\"injected\":true}")).doesNotContain("injected");
        assertThat(CorrelationIdFilter.sanitize("a\u0000b")).doesNotContain("\u0000");
    }

    @Test
    void anOverLongIdIsReplacedWithAGeneratedOne() {
        assertThat(CorrelationIdFilter.sanitize("x".repeat(65))).hasSize(36);
    }

    @Test
    void anAbsentIdIsGenerated() {
        assertThat(CorrelationIdFilter.sanitize(null)).isNotBlank();
        assertThat(CorrelationIdFilter.sanitize("   ")).isNotBlank();
        assertThat(CorrelationIdFilter.sanitize(null))
                .isNotEqualTo(CorrelationIdFilter.sanitize(null));
    }

    @Test
    void theIdIsInTheMdcDuringTheRequestAndGoneAfterwards() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/jobs");
        request.addHeader(CorrelationIdFilter.HEADER, "trace-1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) ->
                assertThat(MDC.get(LogFields.CORRELATION_ID)).isEqualTo("trace-1");

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("trace-1");
        assertThat(MDC.get(LogFields.CORRELATION_ID)).isNull();
    }

    @Test
    void theMdcIsClearedEvenWhenTheRequestFails() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/jobs");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain failing = (req, res) -> {
            throw new IllegalStateException("boom");
        };

        try {
            filter.doFilter(request, response, failing);
        } catch (Exception expected) {
            // the point is what happens next, not the exception itself
        }

        assertThat(MDC.get(LogFields.CORRELATION_ID)).isNull();
    }
}
