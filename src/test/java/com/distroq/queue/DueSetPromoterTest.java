package com.distroq.queue;

import com.distroq.TestProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The contract between Java and the Lua: which Redis keys the script is handed, and in what order.
 *
 * <p>This is worth asserting precisely because the script indexes {@code KEYS} and {@code ARGV}
 * positionally. Reordering {@link com.distroq.model.Priority} or inserting an argument would
 * silently route every HIGH job to the LOW stream, and nothing else in the system would notice.
 * The script never constructs a key name or an enum name itself, which is what makes the mapping
 * checkable from here at all.
 *
 * <p>What the script <em>does</em> — that a future score is left alone, that the limit bounds the
 * batch, that a promoted member is removed — is behaviour of a live Redis and is verified by the
 * A2, A3 and A7 checks in README.md rather than against a mock that would only echo this test's
 * own assumptions back at it.
 */
class DueSetPromoterTest {

    private StringRedisTemplate redis;
    private DueSetPromoter promoter;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        promoter = new DueSetPromoter(redis, new StreamKeys(TestProperties.defaults()));
    }

    @Test
    void theSortedSetIsFirstAndTheTierStreamsFollowInStrictPriorityOrder() {
        promoter.promote("distroq:jobs:scheduled", Instant.now(), 100, EnqueueSource.SCHEDULED);

        assertThat(capturedKeys()).containsExactly(
                "distroq:jobs:scheduled",
                "distroq:jobs:stream:high",
                "distroq:jobs:stream:normal",
                "distroq:jobs:stream:low");
    }

    @Test
    void theArgumentsAreTheCutoffTheLimitTheTierNamesTheDefaultAndTheSource() {
        Instant now = Instant.parse("2026-09-07T15:30:00Z");

        promoter.promote("distroq:jobs:scheduled", now, 25, EnqueueSource.SCHEDULED);

        assertThat(capturedArgs()).containsExactly(
                Long.toString(now.toEpochMilli()),
                "25",
                "HIGH", "NORMAL", "LOW",
                "NORMAL",
                "SCHEDULED");
    }

    @Test
    void theTierNamesLineUpPositionallyWithTheStreamKeys() {
        // ARGV[i + 2] is the name of the tier whose stream is KEYS[i + 1]; if these two lists ever
        // stop agreeing, every job is routed to the wrong stream and nothing throws
        promoter.promote("distroq:jobs:delayed", Instant.now(), 100, EnqueueSource.RETRY);

        List<String> keys = capturedKeys();
        List<Object> args = capturedArgs();
        for (int tier = 0; tier < 3; tier++) {
            assertThat(keys.get(tier + 1))
                    .as("stream for %s", args.get(tier + 2))
                    .endsWith(":" + String.valueOf(args.get(tier + 2)).toLowerCase());
        }
    }

    @Test
    void theSourceLabelIsWhateverTheCallerAskedFor() {
        // the one thing that distinguishes a user-scheduled first run from a retry once both are
        // stream entries, which is how the worker knows which rules to apply
        promoter.promote("distroq:jobs:delayed", Instant.now(), 100, EnqueueSource.RETRY);

        assertThat(capturedArgs()).last().isEqualTo("RETRY");
    }

    @Test
    void theNumberPromotedIsWhatTheScriptReturned() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(4L);

        assertThat(promoter.promote("distroq:jobs:scheduled", Instant.now(), 100,
                EnqueueSource.SCHEDULED)).isEqualTo(4);
    }

    @Test
    void aNullReplyIsZeroPromotedRatherThanANullPointer() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(null);

        assertThat(promoter.promote("distroq:jobs:scheduled", Instant.now(), 100,
                EnqueueSource.SCHEDULED)).isZero();
    }

    @SuppressWarnings("unchecked")
    private List<String> capturedKeys() {
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(redis).execute(any(RedisScript.class), keys.capture(), any(Object[].class));
        return keys.getValue();
    }

    private List<Object> capturedArgs() {
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(redis).execute(any(RedisScript.class), anyList(), args.capture());
        return Arrays.asList(args.getValue());
    }
}
