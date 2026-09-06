package com.distroq.worker;

import com.distroq.config.DistroqProperties;
import com.distroq.model.Priority;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No Redis, no database, no Spring context. That is the point of the class under test: the
 * scheduling policy is separable from the mechanism that carries it out, so it can be exercised
 * exhaustively here and survive v0.5 replacing the queue underneath it.
 */
class PriorityStrategyTest {

    private static final List<Priority> STRICT = List.of(Priority.HIGH, Priority.NORMAL, Priority.LOW);
    private static final List<Priority> GUARD = List.of(Priority.LOW, Priority.NORMAL, Priority.HIGH);

    private static PriorityStrategy strategyWithThreshold(int threshold) {
        return new PriorityStrategy(new DistroqProperties(
                "distroq:jobs:pending",
                "distroq:jobs:delayed",
                new DistroqProperties.Retry(3, 1000L, 60_000L, 0.2, 1000L, 100),
                new DistroqProperties.Dlq(3),
                new DistroqProperties.PriorityTuning(threshold)));
    }

    @Test
    void startsInStrictOrderHighestFirst() {
        PriorityStrategy strategy = strategyWithThreshold(10);

        assertThat(strategy.nextPollOrder()).containsExactlyElementsOf(STRICT);
        assertThat(strategy.guardDue()).isFalse();
        assertThat(strategy.bypassCount()).isZero();
    }

    @Test
    void guardOrderIsTheStrictOrderReversed() {
        // BRPOP returns from the first non-empty key in the order given, so reversing the strict
        // order is what "serve the lowest NON-EMPTY tier" means without asking Redis for depths
        PriorityStrategy strategy = strategyWithThreshold(1);
        strategy.recordServed(Priority.HIGH);

        assertThat(strategy.nextPollOrder()).containsExactlyElementsOf(GUARD);
    }

    @Test
    void staysStrictForExactlyThresholdMinusOneBypasses() {
        PriorityStrategy strategy = strategyWithThreshold(10);

        for (int i = 1; i <= 9; i++) {
            strategy.recordServed(Priority.HIGH);
            assertThat(strategy.bypassCount()).isEqualTo(i);
            assertThat(strategy.nextPollOrder())
                    .as("after %d bypass(es), below the threshold of 10", i)
                    .containsExactlyElementsOf(STRICT);
        }
    }

    @Test
    void tripsToGuardOrderOnTheTenthBypass() {
        PriorityStrategy strategy = strategyWithThreshold(10);

        for (int i = 0; i < 10; i++) {
            strategy.recordServed(Priority.HIGH);
        }

        assertThat(strategy.bypassCount()).isEqualTo(10);
        assertThat(strategy.guardDue()).isTrue();
        assertThat(strategy.nextPollOrder()).containsExactlyElementsOf(GUARD);
    }

    @Test
    void servingTheLowestTierResetsTheCounter() {
        PriorityStrategy strategy = strategyWithThreshold(10);
        for (int i = 0; i < 10; i++) {
            strategy.recordServed(Priority.HIGH);
        }

        strategy.recordServed(Priority.LOW);

        assertThat(strategy.bypassCount()).isZero();
        assertThat(strategy.guardDue()).isFalse();
        assertThat(strategy.nextPollOrder()).containsExactlyElementsOf(STRICT);
    }

    @Test
    void guardIsSingleShotWhenTheLowestTierTurnsOutToBeEmpty() {
        // the guard poll went out in reversed order, LOW had nothing, and BRPOP served NORMAL.
        // The counter must still reset, otherwise the guard latches and NORMAL permanently
        // outranks HIGH - a far worse failure than the starvation it was meant to prevent.
        PriorityStrategy strategy = strategyWithThreshold(10);
        for (int i = 0; i < 10; i++) {
            strategy.recordServed(Priority.HIGH);
        }
        assertThat(strategy.guardDue()).isTrue();

        strategy.recordServed(Priority.NORMAL);

        assertThat(strategy.bypassCount()).isZero();
        assertThat(strategy.guardDue()).isFalse();
        assertThat(strategy.nextPollOrder()).containsExactlyElementsOf(STRICT);
    }

    @Test
    void theEleventhBypassRestartsTheCountRatherThanOverrunningIt() {
        PriorityStrategy strategy = strategyWithThreshold(10);
        for (int i = 0; i < 11; i++) {
            strategy.recordServed(Priority.HIGH);
        }

        assertThat(strategy.bypassCount()).isZero();
        assertThat(strategy.nextPollOrder()).containsExactlyElementsOf(STRICT);
    }

    @Test
    void middleTierCountsAsABypassBecauseItSkipsTheLowestTier() {
        // the counter measures dequeues that bypassed the lowest tier, not HIGH dequeues:
        // a solid stream of NORMAL starves LOW exactly as effectively as a stream of HIGH
        PriorityStrategy strategy = strategyWithThreshold(3);

        strategy.recordServed(Priority.NORMAL);
        strategy.recordServed(Priority.NORMAL);
        assertThat(strategy.nextPollOrder()).containsExactlyElementsOf(STRICT);

        strategy.recordServed(Priority.NORMAL);
        assertThat(strategy.nextPollOrder()).containsExactlyElementsOf(GUARD);
    }

    @Test
    void mixedHigherTiersAccumulateTowardsTheSameThreshold() {
        PriorityStrategy strategy = strategyWithThreshold(4);

        strategy.recordServed(Priority.HIGH);
        strategy.recordServed(Priority.NORMAL);
        strategy.recordServed(Priority.HIGH);
        assertThat(strategy.nextPollOrder()).containsExactlyElementsOf(STRICT);

        strategy.recordServed(Priority.NORMAL);
        assertThat(strategy.nextPollOrder()).containsExactlyElementsOf(GUARD);
    }

    @Test
    void guardFiresOnceEveryThresholdDequeuesUnderSustainedHighLoad() {
        PriorityStrategy strategy = strategyWithThreshold(5);
        int guardPolls = 0;

        // 30 dequeues that all come back HIGH, as if LOW never gets its turn
        for (int i = 0; i < 30; i++) {
            if (strategy.guardDue()) {
                guardPolls++;
            }
            strategy.recordServed(Priority.HIGH);
        }

        assertThat(guardPolls).isEqualTo(5);
    }

    @Test
    void aThresholdBelowOneIsClampedRatherThanDisablingStrictPriorityEntirely() {
        // 0 would make guardDue() permanently true and invert the ordering on every poll
        PriorityStrategy strategy = strategyWithThreshold(0);

        assertThat(strategy.starvationThreshold()).isEqualTo(1);
        assertThat(strategy.nextPollOrder()).containsExactlyElementsOf(STRICT);
    }

    @Test
    void nullTierIsTreatedAsTheDefaultTierRatherThanThrowing() {
        PriorityStrategy strategy = strategyWithThreshold(2);

        strategy.recordServed(null);

        assertThat(strategy.bypassCount()).isEqualTo(1);
    }
}
