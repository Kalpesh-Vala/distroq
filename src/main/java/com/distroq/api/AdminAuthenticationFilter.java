package com.distroq.api;

import com.distroq.api.error.ApiErrors;
import com.distroq.api.error.ErrorCode;
import com.distroq.config.DistroqProperties;
import com.distroq.observability.Events;
import com.distroq.observability.LogContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * A shared bearer token in front of the administrative and idempotency-lookup endpoints.
 *
 * <p>What this is: a release-level guard that stops an unauthenticated caller retrying outbox
 * events, forcing reconciliation, deleting audit-eligible rows or enumerating idempotency keys.
 *
 * <p>What this is not: an identity system. One token means one role, not one person, and
 * {@code X-Admin-Actor} is still a self-declared label rather than a verified subject — a holder
 * of the token can claim to be anyone. The audit trail therefore records who the caller
 * <em>said</em> they were, alongside proof that they held the token. Revocation is a restart with
 * a new value, there is no per-user scoping, and there is no rate limiting. See SECURITY.md.
 *
 * <p>The token is compared with {@link MessageDigest#isEqual} rather than {@code String.equals} so
 * the comparison does not return early on the first differing byte. It is never logged, never
 * written to the database, and never rendered into an error message.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AdminAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthenticationFilter.class);

    private static final String BEARER = "Bearer ";

    /**
     * {@code /api/idempotency} is in here with the admin endpoints on purpose. A key is chosen by
     * the submitter and is often a customer or order identifier, so an open lookup is both an
     * enumeration oracle and a way to read back someone else's job.
     *
     * <p>{@code /api/dashboard} joined the list in v1.1. It is read-only, but it is read-only
     * across the whole system at once — queue depths, lease owners, outbox failures and
     * reconciliation findings in one place — and an aggregate of otherwise-scattered operational
     * detail is exactly the thing that should not be anonymous. The static bundle at
     * {@code /dashboard/} is deliberately not protected: it contains no data and no credentials,
     * and every byte it renders comes from a call that is.
     */
    private static final List<String> PROTECTED_PREFIXES =
            List.of("/api/admin", "/api/idempotency", "/api/dashboard");

    private final ApiErrors errors;
    private final boolean enabled;
    private final byte[] token;

    public AdminAuthenticationFilter(ApiErrors errors, DistroqProperties properties) {
        this.errors = errors;
        DistroqProperties.Admin admin = properties.admin();
        this.enabled = admin.enabled();
        this.token = admin.tokenConfigured()
                ? admin.token().getBytes(StandardCharsets.UTF_8) : null;
        if (enabled && token == null) {
            log.warn("distroq.admin.token is not set: administrative endpoints are UNAUTHENTICATED. "
                    + "This is refused at startup under the production profile.");
        }
    }

    static boolean isProtected(String path) {
        return PROTECTED_PREFIXES.stream()
                .anyMatch(prefix -> path.equals(prefix) || path.startsWith(prefix + "/"));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !isProtected(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!enabled) {
            // authenticated or not, the surface is switched off; 403 rather than 404 because
            // pretending the endpoint does not exist would be a lie an operator has to debug
            errors.write(request, response, ErrorCode.ADMIN_DISABLED,
                    "Administrative endpoints are disabled by configuration");
            return;
        }
        if (token == null) {
            chain.doFilter(request, response);
            return;
        }
        if (!authenticated(request.getHeader("Authorization"))) {
            try (LogContext ignored = LogContext.event(Events.ADMIN_AUTHENTICATION_FAILED)) {
                // the path and method, never the header value or any prefix of it
                log.warn("Rejected unauthenticated administrative request {} {}",
                        request.getMethod(), request.getRequestURI());
            }
            errors.write(request, response, ErrorCode.UNAUTHORIZED,
                    "A valid administrative bearer token is required");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean authenticated(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, BEARER, 0,
                BEARER.length())) {
            return false;
        }
        byte[] presented = authorization.substring(BEARER.length()).trim()
                .getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(presented, token);
    }
}
