package com.distroq.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Ties a request, the log lines it produces and the error body it returns to one identifier.
 *
 * <p>Runs first, before authentication, so that a rejected request is still traceable — the 401
 * carries a correlation ID and so does the log line explaining it, which is what makes "my call
 * failed at 14:03" answerable without a token or a URL in the ticket.
 *
 * <p>An inbound {@code X-Correlation-Id} is honoured so a caller can stitch a distributed trace
 * together, but it is validated first: the value ends up in log files and in a response header, so
 * an unbounded string from an untrusted client is a log-injection vector. Anything that is not a
 * short, printable, punctuation-free token is replaced rather than rejected, because failing a
 * request over a header that only affects observability would be worse than ignoring it.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";

    private static final int MAX_LENGTH = 64;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String correlationId = sanitize(request.getHeader(HEADER));
        MDC.put(LogFields.CORRELATION_ID, correlationId);
        response.setHeader(HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(LogFields.CORRELATION_ID);
        }
    }

    static String sanitize(String supplied) {
        if (supplied == null || supplied.isBlank() || supplied.length() > MAX_LENGTH) {
            return UUID.randomUUID().toString();
        }
        for (int i = 0; i < supplied.length(); i++) {
            char c = supplied.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.';
            if (!allowed) {
                return UUID.randomUUID().toString();
            }
        }
        return supplied;
    }
}
