package com.distroq.queue;

import com.distroq.model.Priority;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The stream body is the only part of v0.5 that another process could write, so both directions
 * are tested: what this application produces, and what it accepts back.
 */
class JobStreamEntryTest {

    private static final UUID JOB_ID = UUID.fromString("f3f86de1-0000-4000-8000-000000000001");

    @Test
    void encodesTheFourFieldsAsStrings() {
        Map<String, String> fields =
                new JobStreamEntry(JOB_ID, Priority.HIGH, 1788715215167L, EnqueueSource.SUBMIT).toFields();

        assertThat(fields).containsExactly(
                Map.entry("jobId", "f3f86de1-0000-4000-8000-000000000001"),
                Map.entry("priority", "HIGH"),
                Map.entry("enqueuedAt", "1788715215167"),
                Map.entry("source", "SUBMIT"));
    }

    @Test
    void everyTierAndSourceRoundTrips() {
        for (Priority priority : Priority.values()) {
            for (EnqueueSource source : EnqueueSource.values()) {
                JobStreamEntry entry = new JobStreamEntry(JOB_ID, priority, 42L, source);

                assertThat(JobStreamEntry.parse(entry.toFields()))
                        .as("%s/%s", priority, source)
                        .contains(entry);
            }
        }
    }

    @Test
    void nowStampsTheCurrentTime() {
        long before = System.currentTimeMillis();
        JobStreamEntry entry = JobStreamEntry.now(JOB_ID, Priority.LOW, EnqueueSource.RETRY);

        assertThat(entry.enqueuedAt()).isBetween(before, System.currentTimeMillis());
    }

    @Test
    void anAbsentPriorityResolvesToTheDefaultRatherThanFailing() {
        assertThat(new JobStreamEntry(JOB_ID, null, 1L, EnqueueSource.SUBMIT).priority())
                .isEqualTo(Priority.NORMAL);
    }

    @Test
    void aMissingJobIdIsRejected() {
        assertThat(JobStreamEntry.parse(Map.of("priority", "HIGH"))).isEmpty();
    }

    @Test
    void anUnparseableJobIdIsRejectedRatherThanThrowing() {
        // a stream entry is data from outside this process; a bad one must not kill the poll loop
        for (String raw : new String[] {"", "   ", "not-a-uuid", "1234", "f3f86de1"}) {
            Map<String, String> fields = new HashMap<>(Map.of("priority", "HIGH"));
            fields.put("jobId", raw);

            assertThat(JobStreamEntry.parse(fields)).as("'%s'", raw).isEmpty();
        }
    }

    @Test
    void aNullFieldMapIsRejected() {
        assertThat(JobStreamEntry.parse(null)).isEmpty();
    }

    @Test
    void anUnknownPriorityFallsBackToTheDefaultBecauseTheDatabaseIsAuthoritative() {
        Optional<JobStreamEntry> parsed = JobStreamEntry.parse(Map.of(
                "jobId", JOB_ID.toString(),
                "priority", "URGENT",
                "enqueuedAt", "5",
                "source", "SUBMIT"));

        assertThat(parsed).isPresent();
        assertThat(parsed.get().priority()).isEqualTo(Priority.NORMAL);
    }

    @Test
    void anUnknownOrMissingSourceReadsAsSubmit() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("jobId", JOB_ID.toString());
        fields.put("priority", "LOW");

        assertThat(JobStreamEntry.parse(fields)).map(JobStreamEntry::source)
                .contains(EnqueueSource.SUBMIT);

        fields.put("source", "TELEPORTED");
        assertThat(JobStreamEntry.parse(fields)).map(JobStreamEntry::source)
                .contains(EnqueueSource.SUBMIT);
    }

    @Test
    void anUnreadableTimestampReadsAsZeroRatherThanRejectingTheEntry() {
        Optional<JobStreamEntry> parsed = JobStreamEntry.parse(Map.of(
                "jobId", JOB_ID.toString(),
                "priority", "LOW",
                "enqueuedAt", "yesterday",
                "source", "RETRY"));

        assertThat(parsed).isPresent();
        assertThat(parsed.get().enqueuedAt()).isZero();
    }

    @Test
    void constructingWithoutAJobIdIsAProgrammingErrorAndThrows() {
        assertThatThrownBy(() -> new JobStreamEntry(null, Priority.HIGH, 1L, EnqueueSource.SUBMIT))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void sourceParsingIsCaseInsensitiveAndRejectsUnknownValues() {
        assertThat(EnqueueSource.parse("legacy_migration")).contains(EnqueueSource.LEGACY_MIGRATION);
        assertThat(EnqueueSource.parse(" Replay ")).contains(EnqueueSource.REPLAY);
        assertThat(EnqueueSource.parse("nonsense")).isEmpty();
        assertThat(EnqueueSource.parse(null)).isEmpty();
    }
}
