package com.distroq.repository;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The retention and claim policies live in SQL, so they are asserted in SQL.
 *
 * <p>A mock repository cannot prove that PENDING is never deleted, because the mock is the thing
 * deciding what comes back. These tests read the queries themselves, which is where the rule is
 * actually written and where a careless edit would break it.
 */
class OutboxRetentionPolicyTest {

    @Test
    void cleanupSelectsOnlyPublishedRows() {
        assertThat(sql("deletablePublished")).contains("published.status = 'PUBLISHED'");
    }

    @Test
    void cleanupNeverSelectsPendingPublishingOrFailedRows() {
        String sql = sql("deletablePublished");

        assertThat(sql)
                .doesNotContain("status = 'PENDING'")
                .doesNotContain("status = 'PUBLISHING'")
                .doesNotContain("status = 'FAILED'");
    }

    @Test
    void cleanupRetainsRowsWhoseJobStillHasAFailedOrInFlightSiblingEvent() {
        assertThat(sql("deletablePublished"))
                .contains("sibling.status IN ('FAILED', 'PUBLISHING')");
    }

    @Test
    void cleanupRetainsRowsWhoseJobIsItselfAnUnresolvedFinding() {
        String sql = sql("deletablePublished");

        assertThat(sql)
                .contains("job.status = 'SCHEDULED' AND job.scheduled_at < :staleBefore")
                .contains("job.status = 'RETRYING' AND job.next_attempt_at < :staleBefore")
                .contains("job.status = 'RUNNING' AND job.execution_lease_until < :staleBefore");
    }

    @Test
    void cleanupIsBatchLimited() {
        assertThat(sql("deletablePublished")).contains("LIMIT :batchSize");
    }

    @Test
    void theRelayNeverClaimsATerminalEvent() {
        String sql = sql("claimable");

        assertThat(sql)
                .contains("status = 'PENDING'")
                .contains("status = 'PUBLISHING' AND locked_until < :now")
                .doesNotContain("FAILED");
    }

    /** Without SKIP LOCKED two instances would serialise on the same batch rather than share it. */
    @Test
    void theRelayClaimIsSafeForMoreThanOneInstance() {
        assertThat(sql("claimable")).contains("FOR UPDATE SKIP LOCKED");
    }

    private static String sql(String methodName) {
        Method method = Arrays.stream(OutboxEventRepository.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No method named " + methodName));
        Query query = method.getAnnotation(Query.class);
        assertThat(query).as("@Query on %s", methodName).isNotNull();
        assertThat(query.nativeQuery()).as("%s must be a native query", methodName).isTrue();
        return query.value();
    }
}
