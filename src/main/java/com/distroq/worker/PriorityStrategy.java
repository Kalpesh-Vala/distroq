package com.distroq.worker;

import com.distroq.config.DistroqProperties;
import com.distroq.model.Priority;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Strict priority with a bounded-starvation guard, expressed purely as an ordering decision.
 *
 * <p>Deliberately mechanism-agnostic: it never touches Redis, holds no keys, and knows nothing
 * about how a tier is read. It answers one question — in what order should the tiers be
 * attempted next — and counts what came back. That claim was tested by v0.5 replacing the lists
 * with Streams: this class is unchanged apart from its comments, while everything in the queue
 * package underneath it was rewritten.
 *
 * <p><b>The counter.</b> It counts consecutive deliveries served from a tier above the lowest, and
 * resets when the lowest tier is served. After {@code starvation-threshold} of them it asks for
 * the reversed order, which sends the next read to the lowest tier first.
 *
 * <p><b>What that measures, and what it does not.</b> Ideally the counter would only advance when
 * a lower tier actually had work waiting — repeatedly serving HIGH with nothing in LOW is not
 * starvation, it is an empty queue. Knowing that requires tier depths, which would mean either
 * reading Redis from here (destroying the property just demonstrated) or a depth check before
 * every read (a second round trip, and a stale answer by the time it is used). The trade-off
 * taken instead is to over-count and make the guard self-cancelling: when the guard fires and the
 * lowest tier turns out to be empty, the read walks on to the next tier in the reversed order and
 * {@code recordServed} resets the counter anyway. So a spurious guard costs exactly one poll in a
 * non-preferred order, once every {@code threshold} deliveries, and cannot latch on — which is the
 * failure that matters, because a latched guard would invert priority permanently.
 */
@Component
public class PriorityStrategy {

    private static final List<Priority> STRICT_ORDER = Priority.STRICT_ORDER;
    private static final List<Priority> GUARD_ORDER = reversed(STRICT_ORDER);

    private final int starvationThreshold;

    /**
     * Atomic because v0.7 adds worker threads. It makes this class safe to share; it does not make
     * the guarantee global — see NOTES.md.
     */
    private final AtomicInteger bypasses = new AtomicInteger();

    public PriorityStrategy(DistroqProperties properties) {
        this.starvationThreshold = Math.max(1, properties.priority().starvationThreshold());
    }

    /**
     * The tiers to attempt, in order. Strict (highest first) normally; reversed when the guard is
     * due, which puts the lowest tier first because the consumer reads them in the order given and
     * stops at the first that answers.
     */
    public List<Priority> nextPollOrder() {
        return guardDue() ? GUARD_ORDER : STRICT_ORDER;
    }

    /** True when the next poll will use the guard order. Exposed so the worker can log it. */
    public boolean guardDue() {
        return bypasses.get() >= starvationThreshold;
    }

    /**
     * Serving the lowest tier resets the counter. Serving anything above it advances the counter,
     * except when the guard was already due — in which case the guard has just had its turn and
     * the counter restarts whether or not the lowest tier had anything to give.
     */
    public void recordServed(Priority tier) {        if (Priority.orDefault(tier) == Priority.LOWEST) {
            bypasses.set(0);
            return;
        }
        bypasses.updateAndGet(count -> count >= starvationThreshold ? 0 : count + 1);
    }

    /** Consecutive deliveries served above the lowest tier since the guard last fired. */
    public int bypassCount() {
        return bypasses.get();
    }

    public int starvationThreshold() {
        return starvationThreshold;
    }

    private static List<Priority> reversed(List<Priority> order) {
        List<Priority> copy = new ArrayList<>(order);
        Collections.reverse(copy);
        return List.copyOf(copy);
    }
}
