package com.distroq.queue;

import com.distroq.TestProperties;
import com.distroq.model.Priority;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StreamKeysTest {

    private final StreamKeys keys = new StreamKeys(TestProperties.defaults());

    @Test
    void eachTierMapsToItsOwnStream() {
        assertThat(keys.keyFor(Priority.HIGH)).isEqualTo("distroq:jobs:stream:high");
        assertThat(keys.keyFor(Priority.NORMAL)).isEqualTo("distroq:jobs:stream:normal");
        assertThat(keys.keyFor(Priority.LOW)).isEqualTo("distroq:jobs:stream:low");
    }

    @Test
    void keysAreDerivedFromTheEnumSoTheSetCannotDriftOutOfSync() {
        assertThat(keys.all()).hasSameSizeAs(Priority.values());
        for (Priority priority : Priority.values()) {
            assertThat(keys.keyFor(priority)).endsWith(':' + priority.keySuffix());
        }
    }

    @Test
    void allIsInStrictPriorityOrder() {
        assertThat(keys.all()).containsExactly(
                "distroq:jobs:stream:high",
                "distroq:jobs:stream:normal",
                "distroq:jobs:stream:low");
    }

    @Test
    void aNullTierResolvesToTheDefaultStream() {
        assertThat(keys.keyFor(null)).isEqualTo(keys.keyFor(Priority.DEFAULT));
    }

    @Test
    void theKeyOfAStreamIdentifiesItsTier() {
        for (Priority priority : Priority.values()) {
            assertThat(keys.priorityFor(keys.keyFor(priority))).contains(priority);
        }
    }

    @Test
    void aForeignKeyHasNoTierRatherThanASilentDefault() {
        assertThat(keys.priorityFor("distroq:jobs:pending:high")).isEmpty();
        assertThat(keys.priorityFor("some:other:stream")).isEmpty();
    }
}
