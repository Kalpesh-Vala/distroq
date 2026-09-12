package com.distroq.dashboard;

import com.distroq.api.error.ApiErrors;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The guarantee the whole v1.1 phase rests on: the dashboard API cannot change anything.
 *
 * <p>Spring MVC would already answer 405 for an unmapped method, so these tests pass today whether
 * or not the filter exists. That is the point. They fail the moment someone maps a write onto
 * {@code /api/dashboard}, which is the change this is here to catch.
 */
class DashboardReadOnlyFilterTest {

    private final DashboardReadOnlyFilter filter = new DashboardReadOnlyFilter(
            new ApiErrors(JsonMapper.builder().findAndAddModules().build()));

    @Test
    void everyDashboardApiPathIsCovered() {
        assertThat(DashboardReadOnlyFilter.isDashboardApi("/api/dashboard")).isTrue();
        assertThat(DashboardReadOnlyFilter.isDashboardApi("/api/dashboard/overview")).isTrue();
        assertThat(DashboardReadOnlyFilter.isDashboardApi("/api/dashboard/jobs/abc")).isTrue();
    }

    @Test
    void aPathThatMerelyStartsWithTheSamePrefixIsNotCovered() {
        assertThat(DashboardReadOnlyFilter.isDashboardApi("/api/dashboards")).isFalse();
        assertThat(DashboardReadOnlyFilter.isDashboardApi("/api/jobs")).isFalse();
        assertThat(DashboardReadOnlyFilter.isDashboardApi("/dashboard/index.html")).isFalse();
    }

    @Test
    void readsAreLetThrough() throws Exception {
        for (String method : new String[] {"GET", "HEAD", "OPTIONS", "get"}) {
            MockHttpServletRequest request = request(method, "/api/dashboard/overview");
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            filter.doFilter(request, response, chain);

            verify(chain).doFilter(request, response);
            assertThat(response.getStatus()).isEqualTo(200);
        }
    }

    @Test
    void everyWriteMethodIsRefusedWith405() throws Exception {
        for (String method : new String[] {"POST", "PUT", "PATCH", "DELETE"}) {
            MockHttpServletRequest request = request(method, "/api/dashboard/outbox");
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            filter.doFilter(request, response, chain);

            assertThat(response.getStatus()).isEqualTo(405);
            assertThat(response.getHeader("Allow")).isEqualTo("GET, HEAD, OPTIONS");
            verify(chain, never()).doFilter(any(), any());
        }
    }

    @Test
    void theRefusalUsesTheV1ErrorContract() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request("POST", "/api/dashboard/reconciliation"), response,
                new MockFilterChain());

        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString())
                .contains("\"code\":\"METHOD_NOT_ALLOWED\"")
                .contains("\"status\":405")
                .contains("/api/dashboard/reconciliation");
    }

    @Test
    void nothingOutsideTheDashboardApiIsTouched() throws Exception {
        MockHttpServletRequest submit = request("POST", "/api/jobs");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(submit, response, chain);

        // shouldNotFilter means the chain continues untouched, so job submission still works
        verify(chain).doFilter(submit, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        return request;
    }
}
