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

        public static final DistroqProperties.Outbox OUTBOX =
                new DistroqProperties.Outbox(500L, 100, 30_000L, 100, 604_800_000L, 30, 90,
                        3_600_000L, 500, true, false);

        public static final DistroqProperties.Worker WORKER =
            new DistroqProperties.Worker(1, 30_000L, 5_000L);

    public static final DistroqProperties.Reconciliation RECONCILIATION =
            new DistroqProperties.Reconciliation(true, 30_000L, 100, 60_000L, 60_000L, 60_000L,
                    false, false);

    public static final DistroqProperties.Effects EFFECTS =
            new DistroqProperties.Effects(true, 300_000L, false);

    public static final DistroqProperties.Admin ADMIN = new DistroqProperties.Admin(500);

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

    public static DistroqProperties of(DistroqProperties.Outbox outbox) {
        return new DistroqProperties(
                "distroq:jobs:pending",
                "distroq:jobs:delayed",
                "distroq:jobs:scheduled",
                "distroq:jobs:stream",
                RETRY,
                DLQ,
                PRIORITY,
                STREAMS,
                SCHEDULING,
                outbox,
                WORKER,
                RECONCILIATION,
                EFFECTS,
                ADMIN);
    }

    public static DistroqProperties of(DistroqProperties.Reconciliation reconciliation) {
        return new DistroqProperties(
                "distroq:jobs:pending",
                "distroq:jobs:delayed",
                "distroq:jobs:scheduled",
                "distroq:jobs:stream",
                RETRY,
                DLQ,
                PRIORITY,
                STREAMS,
                SCHEDULING,
                OUTBOX,
                WORKER,
                reconciliation,
                EFFECTS,
                ADMIN);
    }

    public static DistroqProperties of(DistroqProperties.Effects effects) {        return new DistroqProperties(
                "distroq:jobs:pending",
                "distroq:jobs:delayed",
                "distroq:jobs:scheduled",
                "distroq:jobs:stream",
                RETRY,
                DLQ,
                PRIORITY,
                STREAMS,
                SCHEDULING,
                OUTBOX,
                WORKER,
                RECONCILIATION,
                effects,
                ADMIN);
    }

    public static DistroqProperties of(DistroqProperties.Admin admin) {
        return new DistroqProperties(
                "distroq:jobs:pending",
                "distroq:jobs:delayed",
                "distroq:jobs:scheduled",
                "distroq:jobs:stream",
                RETRY,
                DLQ,
                PRIORITY,
                STREAMS,
                SCHEDULING,
                OUTBOX,
                WORKER,
                RECONCILIATION,
                EFFECTS,
                admin);
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
                scheduling,
                OUTBOX,
                WORKER,
                RECONCILIATION,
                EFFECTS,
                ADMIN);
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
                SCHEDULING,
                OUTBOX,
                WORKER,
                RECONCILIATION,
                EFFECTS,
                ADMIN);
    }
}
