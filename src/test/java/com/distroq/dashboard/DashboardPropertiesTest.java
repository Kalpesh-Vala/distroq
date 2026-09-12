package com.distroq.dashboard;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The page-size ceiling.
 *
 * <p>An unbounded page on an operations dashboard is a denial of service with a friendly URL:
 * {@code ?size=1000000} against the jobs table, from a browser, every five seconds. The clamp is
 * applied after the request rather than validated before it, so an over-large request succeeds
 * with fewer rows instead of failing — and the response says which size was used.
 */
class DashboardPropertiesTest {

    private static final DashboardProperties DEFAULTS = new DashboardProperties(
            true, 5000L, 50, 200, 100, 86_400_000L, 30_000L, 60_000L, "analytics/output",
            "dashboard/dist", 5000L);

    @Test
    void anAbsentSizeIsTheConfiguredDefault() {
        assertThat(DEFAULTS.pageSize(null)).isEqualTo(50);
    }

    @Test
    void aRequestedSizeIsHonouredUpToTheMaximum() {
        assertThat(DEFAULTS.pageSize(10)).isEqualTo(10);
        assertThat(DEFAULTS.pageSize(200)).isEqualTo(200);
    }

    @Test
    void anOverLargeRequestIsClampedRatherThanRejected() {
        assertThat(DEFAULTS.pageSize(1_000_000)).isEqualTo(200);
        assertThat(DEFAULTS.pageSize(Integer.MAX_VALUE)).isEqualTo(200);
    }

    @Test
    void aNonPositiveSizeBecomesOne() {
        assertThat(DEFAULTS.pageSize(0)).isEqualTo(1);
        assertThat(DEFAULTS.pageSize(-5)).isEqualTo(1);
    }

    @Test
    void aDefaultLargerThanTheMaximumStillCannotExceedIt() {
        DashboardProperties misconfigured = new DashboardProperties(true, 5000L, 500, 100, 100,
                86_400_000L, 30_000L, 60_000L, "analytics/output", "dashboard/dist", 5000L);

        assertThat(misconfigured.pageSize(null)).isEqualTo(100);
    }

    @Test
    void pageNumbersAreNeverNegative() {
        assertThat(DEFAULTS.pageNumber(null)).isZero();
        assertThat(DEFAULTS.pageNumber(-3)).isZero();
        assertThat(DEFAULTS.pageNumber(7)).isEqualTo(7);
    }
}
