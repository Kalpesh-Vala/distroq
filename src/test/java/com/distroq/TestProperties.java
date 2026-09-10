package com.distroq;

import com.distroq.config.DistroqProperties;

/**
 * Positional record construction, in one place. Every version so far has added a component to
 * {@link DistroqProperties}, and each time every test that built one by hand had to be edited.
 *
 * <p>v1.0 collapses the seven near-identical factory methods into one {@link Builder}, so the next
 * component costs one field here and nothing anywhere else.
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

    /** Enabled with no token: the shape a laptop runs in, where the bearer guard is inactive. */
    public static final DistroqProperties.Admin ADMIN =
            new DistroqProperties.Admin(true, null, "admin", 500);

    public static final DistroqProperties.Shutdown SHUTDOWN =
            new DistroqProperties.Shutdown(30_000L, 10_000L, 10_000L);

    public static final DistroqProperties.Metrics METRICS =
            new DistroqProperties.Metrics(true, 20, 5_000L);

    private TestProperties() {
    }

    public static DistroqProperties defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static DistroqProperties of(DistroqProperties.Retry retry) {
        return builder().retry(retry).build();
    }

    public static DistroqProperties of(DistroqProperties.PriorityTuning priority) {
        return builder().priority(priority).build();
    }

    public static DistroqProperties of(DistroqProperties.Streams streams) {
        return builder().streams(streams).build();
    }

    public static DistroqProperties of(DistroqProperties.Outbox outbox) {
        return builder().outbox(outbox).build();
    }

    public static DistroqProperties of(DistroqProperties.Reconciliation reconciliation) {
        return builder().reconciliation(reconciliation).build();
    }

    public static DistroqProperties of(DistroqProperties.Effects effects) {
        return builder().effects(effects).build();
    }

    public static DistroqProperties of(DistroqProperties.Admin admin) {
        return builder().admin(admin).build();
    }

    public static DistroqProperties of(DistroqProperties.Scheduling scheduling) {
        return builder().scheduling(scheduling).build();
    }

    public static DistroqProperties of(DistroqProperties.Worker worker) {
        return builder().worker(worker).build();
    }

    public static DistroqProperties of(DistroqProperties.Retry retry,
                                       DistroqProperties.PriorityTuning priority,
                                       DistroqProperties.Streams streams) {
        return builder().retry(retry).priority(priority).streams(streams).build();
    }

    public static final class Builder {

        private String queueKey = "distroq:jobs:pending";
        private String delayedKey = "distroq:jobs:delayed";
        private String scheduledKey = "distroq:jobs:scheduled";
        private String streamKey = "distroq:jobs:stream";
        private DistroqProperties.Retry retry = RETRY;
        private DistroqProperties.Dlq dlq = DLQ;
        private DistroqProperties.PriorityTuning priority = PRIORITY;
        private DistroqProperties.Streams streams = STREAMS;
        private DistroqProperties.Scheduling scheduling = SCHEDULING;
        private DistroqProperties.Outbox outbox = OUTBOX;
        private DistroqProperties.Worker worker = WORKER;
        private DistroqProperties.Reconciliation reconciliation = RECONCILIATION;
        private DistroqProperties.Effects effects = EFFECTS;
        private DistroqProperties.Admin admin = ADMIN;
        private DistroqProperties.Shutdown shutdown = SHUTDOWN;
        private DistroqProperties.Metrics metrics = METRICS;

        public Builder queueKey(String value) {
            this.queueKey = value;
            return this;
        }

        public Builder delayedKey(String value) {
            this.delayedKey = value;
            return this;
        }

        public Builder scheduledKey(String value) {
            this.scheduledKey = value;
            return this;
        }

        public Builder streamKey(String value) {
            this.streamKey = value;
            return this;
        }

        public Builder retry(DistroqProperties.Retry value) {
            this.retry = value;
            return this;
        }

        public Builder dlq(DistroqProperties.Dlq value) {
            this.dlq = value;
            return this;
        }

        public Builder priority(DistroqProperties.PriorityTuning value) {
            this.priority = value;
            return this;
        }

        public Builder streams(DistroqProperties.Streams value) {
            this.streams = value;
            return this;
        }

        public Builder scheduling(DistroqProperties.Scheduling value) {
            this.scheduling = value;
            return this;
        }

        public Builder outbox(DistroqProperties.Outbox value) {
            this.outbox = value;
            return this;
        }

        public Builder worker(DistroqProperties.Worker value) {
            this.worker = value;
            return this;
        }

        public Builder reconciliation(DistroqProperties.Reconciliation value) {
            this.reconciliation = value;
            return this;
        }

        public Builder effects(DistroqProperties.Effects value) {
            this.effects = value;
            return this;
        }

        public Builder admin(DistroqProperties.Admin value) {
            this.admin = value;
            return this;
        }

        public Builder shutdown(DistroqProperties.Shutdown value) {
            this.shutdown = value;
            return this;
        }

        public Builder metrics(DistroqProperties.Metrics value) {
            this.metrics = value;
            return this;
        }

        public DistroqProperties build() {
            return new DistroqProperties(queueKey, delayedKey, scheduledKey, streamKey, retry, dlq,
                    priority, streams, scheduling, outbox, worker, reconciliation, effects, admin,
                    shutdown, metrics);
        }
    }
}
