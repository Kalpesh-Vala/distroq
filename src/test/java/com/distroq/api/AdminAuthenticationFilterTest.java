package com.distroq.api;

import com.distroq.TestProperties;
import com.distroq.api.error.ApiErrors;
import com.distroq.config.DistroqProperties;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The bearer guard in front of the administrative surface.
 *
 * <p>Every assertion about the response body checks that it does <em>not</em> contain the token.
 * The one way this class can fail catastrophically is by echoing the value it is comparing
 * against, and that is the kind of thing a reviewer stops noticing after the third read.
 */
class AdminAuthenticationFilterTest {

    private static final String TOKEN = "correct-horse-battery-staple";

    // the same shape Boot builds: without the JSR-310 module an Instant cannot be serialised
    private final ApiErrors errors =
            new ApiErrors(JsonMapper.builder().findAndAddModules().build());

    @Test
    void everyAdminPathIsProtected() {
        assertThat(AdminAuthenticationFilter.isProtected("/api/admin")).isTrue();
        assertThat(AdminAuthenticationFilter.isProtected("/api/admin/outbox")).isTrue();
        assertThat(AdminAuthenticationFilter.isProtected("/api/admin/reconciliation/run")).isTrue();
    }

    @Test
    void theIdempotencyLookupIsProtectedBecauseAKeyIsChosenByTheSubmitter() {
        assertThat(AdminAuthenticationFilter.isProtected("/api/idempotency/order-42")).isTrue();
    }

    @Test
    void theReadOnlyDashboardApiIsProtectedToo() {
        // read-only, but read-only across the whole system at once; see the class comment
        assertThat(AdminAuthenticationFilter.isProtected("/api/dashboard")).isTrue();
        assertThat(AdminAuthenticationFilter.isProtected("/api/dashboard/overview")).isTrue();
        assertThat(AdminAuthenticationFilter.isProtected("/api/dashboard/jobs/abc")).isTrue();
    }

    @Test
    void theDashboardBundleItselfIsNotProtectedBecauseItHoldsNoData() {
        assertThat(AdminAuthenticationFilter.isProtected("/dashboard/")).isFalse();
        assertThat(AdminAuthenticationFilter.isProtected("/dashboard/assets/index.js")).isFalse();
    }

    @Test
    void ordinaryJobEndpointsAreNotProtected() {
        assertThat(AdminAuthenticationFilter.isProtected("/api/jobs")).isFalse();
        assertThat(AdminAuthenticationFilter.isProtected("/api/dlq")).isFalse();
        assertThat(AdminAuthenticationFilter.isProtected("/api/metrics")).isFalse();
        assertThat(AdminAuthenticationFilter.isProtected("/actuator/health")).isFalse();
    }

    @Test
    void aPathThatMerelyStartsWithTheSamePrefixIsNotProtected() {
        // /api/administrators is a different endpoint, not a sub-path of /api/admin
        assertThat(AdminAuthenticationFilter.isProtected("/api/administrators")).isFalse();
    }

    @Test
    void aMissingTokenIsRejectedWith401() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter(TOKEN).doFilter(get("/api/admin/outbox"), response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).startsWith("Bearer");
        assertThat(response.getContentAsString()).contains("\"code\":\"UNAUTHORIZED\"");
        verify(chain, never()).doFilter(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void anIncorrectTokenIsRejectedWith401() throws Exception {
        MockHttpServletRequest request = get("/api/admin/outbox");
        request.addHeader("Authorization", "Bearer not-the-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(TOKEN).doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void aTokenThatIsAPrefixOfTheRealOneIsRejected() throws Exception {
        MockHttpServletRequest request = get("/api/admin/outbox");
        request.addHeader("Authorization", "Bearer " + TOKEN.substring(0, TOKEN.length() - 1));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(TOKEN).doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void aCorrectTokenIsLetThrough() throws Exception {
        MockHttpServletRequest request = get("/api/admin/outbox");
        request.addHeader("Authorization", "Bearer " + TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter(TOKEN).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain).doFilter(request, response);
    }

    @Test
    void theSchemeIsMatchedCaseInsensitivelyButTheTokenIsNot() throws Exception {
        MockHttpServletRequest lowerCaseScheme = get("/api/admin/outbox");
        lowerCaseScheme.addHeader("Authorization", "bearer " + TOKEN);
        MockHttpServletResponse accepted = new MockHttpServletResponse();
        filter(TOKEN).doFilter(lowerCaseScheme, accepted, new MockFilterChain());
        assertThat(accepted.getStatus()).isEqualTo(200);

        MockHttpServletRequest upperCaseToken = get("/api/admin/outbox");
        upperCaseToken.addHeader("Authorization", "Bearer " + TOKEN.toUpperCase());
        MockHttpServletResponse rejected = new MockHttpServletResponse();
        filter(TOKEN).doFilter(upperCaseToken, rejected, new MockFilterChain());
        assertThat(rejected.getStatus()).isEqualTo(401);
    }

    @Test
    void theRejectionBodyNeverContainsTheTokenOrAnyPartOfIt() throws Exception {
        MockHttpServletRequest request = get("/api/admin/outbox");
        request.addHeader("Authorization", "Bearer wrong");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(TOKEN).doFilter(request, response, new MockFilterChain());

        assertThat(response.getContentAsString())
                .doesNotContain(TOKEN)
                .doesNotContain(TOKEN.substring(0, 8))
                .doesNotContain("wrong");
    }

    @Test
    void anUnprotectedPathIsNotFilteredAtAll() {
        assertThat(filter(TOKEN).shouldNotFilter(get("/api/jobs"))).isTrue();
    }

    @Test
    void withNoTokenConfiguredTheGuardIsInactiveSoALaptopNeedsNoSecret() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter(null).doFilter(get("/api/admin/outbox"), response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain).doFilter(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void aDisabledAdminSurfaceReturns403EvenWithTheRightToken() throws Exception {
        MockHttpServletRequest request = get("/api/admin/outbox");
        request.addHeader("Authorization", "Bearer " + TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();

        AdminAuthenticationFilter disabled = new AdminAuthenticationFilter(errors,
                TestProperties.of(new DistroqProperties.Admin(false, TOKEN, "admin", 500)));
        disabled.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("\"code\":\"ADMIN_DISABLED\"");
    }

    private AdminAuthenticationFilter filter(String token) {
        return new AdminAuthenticationFilter(errors,
                TestProperties.of(new DistroqProperties.Admin(true, token, "admin", 500)));
    }

    private static MockHttpServletRequest get(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRequestURI(path);
        return request;
    }
}
