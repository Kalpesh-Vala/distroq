package com.distroq.queue;

import com.distroq.model.Priority;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The routing information a sorted set cannot hold any other way. A member that does not encode
 * its tier can only be promoted by looking the job up in PostgreSQL, which is exactly the
 * per-job round trip the atomic promotion script exists to avoid.
 */
class SortedSetMemberTest {

    private static final UUID JOB_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    void highEncodesAsItsTierThenTheId() {
        assertThat(SortedSetMember.encode(JOB_ID, Priority.HIGH))
                .isEqualTo("HIGH:11111111-2222-3333-4444-555555555555");
    }

    @Test
    void normalEncodesAsItsTierThenTheId() {
        assertThat(SortedSetMember.encode(JOB_ID, Priority.NORMAL))
                .isEqualTo("NORMAL:11111111-2222-3333-4444-555555555555");
    }

    @Test
    void lowEncodesAsItsTierThenTheId() {
        assertThat(SortedSetMember.encode(JOB_ID, Priority.LOW))
                .isEqualTo("LOW:11111111-2222-3333-4444-555555555555");
    }

    @Test
    void aNullTierEncodesAsTheDefaultRatherThanThrowing() {
        assertThat(SortedSetMember.encode(JOB_ID, null)).startsWith("NORMAL:");
    }

    @Test
    void everyTierSurvivesAnEncodeParseRoundTrip() {
        for (Priority priority : Priority.values()) {
            String member = SortedSetMember.encode(JOB_ID, priority);

            assertThat(SortedSetMember.parse(member)).as(priority.name())
                    .contains(new SortedSetMember(JOB_ID, priority));
        }
    }

    @Test
    void theIdIsRecoverableFromTheMember() {
        assertThat(SortedSetMember.parse("LOW:" + JOB_ID))
                .get()
                .extracting(SortedSetMember::jobId)
                .isEqualTo(JOB_ID);
    }

    @Test
    void theTierIsCaseInsensitiveOnTheWayIn() {
        // nothing this application writes is lowercase, but a member typed by hand during an
        // incident should still be readable rather than silently uncounted
        assertThat(SortedSetMember.parse("high:" + JOB_ID))
                .get()
                .extracting(SortedSetMember::priority)
                .isEqualTo(Priority.HIGH);
    }

    @Test
    void aBareUuidIsNotAMemberBecauseItCarriesNoTier() {
        assertThat(SortedSetMember.parse(JOB_ID.toString())).isEmpty();
    }

    @Test
    void anUnknownTierIsRejectedRatherThanCountedAsNormal() {
        // Priority.parse resolves absence to NORMAL, which is right for a missing request field
        // and wrong here: putting an unrecognised member in the NORMAL bucket would report a
        // depth that does not exist
        assertThat(SortedSetMember.parse("URGENT:" + JOB_ID)).isEmpty();
    }

    @Test
    void aMemberThatStartsWithTheSeparatorIsRejected() {
        assertThat(SortedSetMember.parse(":" + JOB_ID)).isEmpty();
    }

    @Test
    void aMalformedIdIsRejected() {
        assertThat(SortedSetMember.parse("HIGH:not-a-uuid")).isEmpty();
    }

    @Test
    void anEmptyOrNullMemberIsRejectedWithoutThrowing() {
        assertThat(SortedSetMember.parse(null)).isEmpty();
        assertThat(SortedSetMember.parse("")).isEmpty();
        assertThat(SortedSetMember.parse(":")).isEmpty();
    }
}
