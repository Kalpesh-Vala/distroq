package com.distroq;

import com.distroq.config.DistroqProperties;

/**
 * Positional record construction, in one place. Every version so far has added a component to
 * {@link DistroqProperties}, and each time every test that built one by hand had to be edited.
 */
public final class TestProperties {

    public static final DistroqProperties.Retry RETRY =
            new DistroqProperties.Retry(3, 1000L, 60_000L, 0.2, 1000L, 100);

    public static final DistroqProperties.Dlq DLQ = new DistroqProperties.Dlq(3);

    public static final DistroqProperties.PriorityTuning PRIORITY =
            new DistroqProperties.PriorityTuning(10);

    public static final DistroqProperties.Streams STREAMS =
            new DistroqProperties.Streams("distroq-workers", "worker", 10_000L, 100, 1, 1000L, "0");

    public static final DistroqProperties.Scheduling SCHEDULING =
            new DistroqProperties.Scheduling(1000L, 100);

    private TestProperties() {
    }

    public static DistroqProperties defaults() {
        return of(RETRY, PRIORITY, STREAMS);
    }

    public static DistroqProperties of(DistroqProperties.Retry retry) {
        return of(retry, PRIORITY, STREAMS);
    }

    public static DistroqProperties of(DistroqProperties.PriorityTuning priority) {
        return of(RETRY, priority, STREAMS);
    }

    public static DistroqProperties of(DistroqProperties.Streams streams) {
        return of(RETRY, PRIORITY, streams);
    }

    public static DistroqProperties of(DistroqProperties.Scheduling scheduling) {
        return new DistroqProperties(
                "distroq:jobs:pending",
                "distroq:jobs:delayed",
                "distroq:jobs:scheduled",
                "distroq:jobs:stream",
                RETRY,
                DLQ,
                PRIORITY,
                STREAMS,
                scheduling);
    }

    public static DistroqProperties of(DistroqProperties.Retry retry,
                                       DistroqProperties.PriorityTuning priority,
                                       DistroqProperties.Streams streams) {
        return new DistroqProperties(
                "distroq:jobs:pending",
                "distroq:jobs:delayed",
                "distroq:jobs:scheduled",
                "distroq:jobs:stream",
                retry,
                DLQ,
                priority,
                streams,
                SCHEDULING);
    }
}
