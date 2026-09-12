package com.distroq.dashboard;

import com.distroq.api.error.ApiErrors;
import com.distroq.api.error.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

/**
 * Refuses anything but a read on {@code /api/dashboard}.
 *
 * <p>Spring MVC would already answer 405 for a method no handler is mapped to, which makes this
 * filter redundant in the sense that it changes no current behaviour. It is here for the case it
 * is not redundant in: the moment someone adds a {@code @PostMapping} to the dashboard controller,
 * or maps a write onto one of these paths from somewhere else, this filter is what fails the
 * request and the test that pins it is what fails the build. The read-only guarantee in README.md
 * is then enforced by code rather than by review.
 *
 * <p>It runs before authentication so that a write attempt is rejected as a write attempt whether
 * or not the caller holds a token, and so a probe cannot use the status difference between 401 and
 * 405 to learn anything about the token.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class DashboardReadOnlyFilter extends OncePerRequestFilter {

    static final String PREFIX = "/api/dashboard";

    /** HEAD and OPTIONS are reads: one is a GET without a body, the other is CORS preflight. */
    private static final Set<String> READ_METHODS =
            Set.of(HttpMethod.GET.name(), HttpMethod.HEAD.name(), HttpMethod.OPTIONS.name());

    private final ApiErrors errors;

    public DashboardReadOnlyFilter(ApiErrors errors) {
        this.errors = errors;
    }

    static boolean isDashboardApi(String path) {
        return path != null && (path.equals(PREFIX) || path.startsWith(PREFIX + "/"));
    }

    static boolean isRead(String method) {
        return method != null && READ_METHODS.contains(method.toUpperCase(java.util.Locale.ROOT));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !isDashboardApi(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!isRead(request.getMethod())) {
            // RFC 9110: a 405 must say what is allowed. ApiErrors resets the response before it
            // writes, so the header goes through it rather than being set here and cleared there.
            errors.write(request, response, ErrorCode.METHOD_NOT_ALLOWED,
                    "The dashboard API is read-only; use the administrative API for operations "
                            + "that change state",
                    Map.of("Allow", "GET, HEAD, OPTIONS"));
            return;
        }
        chain.doFilter(request, response);
    }
}
