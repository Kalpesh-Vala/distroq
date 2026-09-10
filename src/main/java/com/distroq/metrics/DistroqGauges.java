package com.distroq.metrics;

import com.distroq.config.DistroqProperties;
import com.distroq.effects.EffectMetrics;
import com.distroq.repository.JobRepository;
import com.distroq.worker.WorkerMetrics;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * The state metrics: gauges that answer "how many right now" and function counters that expose a
 * running total the database already keeps.
 *
 * <p>Kept apart from {@link DistroqMetrics} because their failure modes are different. A counter
 * there is incremented by the thread that did the work and cannot be wrong; a gauge here is a
 * query that can fail, can be stale by up to {@code distroq.metrics.database-gauge-cache-ms}, and
 * is the same value on every instance rather than a per-process tally. README.md says which is
 * which for every metric, because reading a database-derived total as a process counter is how
 * someone concludes a restart lost data.
 *
 * <p>{@code distroq.reconciliation.repairs} and {@code distroq.effect.applications} are function
 * counters rather than gauges: they only ever increase, so Prometheus should treat them as
 * counters and render them with a {@code _total} suffix, even though the number comes from a
 * {@code COUNT(*)} rather than from an increment in this process.
 */
@Configuration(proxyBeanMethods = false)
public class DistroqGauges {

    private static final Logger log = LoggerFactory.getLogger(DistroqGauges.class);

    public DistroqGauges(MeterRegistry registry,
                         ReliabilitySnapshotCache reliability,
                         WorkerMetrics workerMetrics,
                         EffectMetrics effectMetrics,
                         JobRepository jobRepository,
                         DistroqProperties properties) {

        gauge(registry, "distroq.outbox.pending", null,
                "Outbox events durably recorded and not yet published",
                () -> reliability.value("outboxPending"));
        gauge(registry, "distroq.outbox.retryable_failed", null,
                "Outbox events that failed an attempt and will be claimed again",
                () -> reliability.value("outboxRetryableFailed"));
        gauge(registry, "distroq.outbox.terminal_failed", null,
                "Outbox events the relay has given up on until an operator retries them",
                () -> reliability.value("outboxTerminalFailed"));
        gauge(registry, "distroq.outbox.oldest.age", "seconds",
                "Age of the oldest unpublished outbox event",
                () -> reliability.value("outboxOldestAgeMs") / 1000d);
        gauge(registry, "distroq.reconciliation.findings", null,
                "Inconsistencies reconciliation would report if it ran now",
                () -> reliability.value("reconciliationFindings"));
        gauge(registry, "distroq.execution_leases.expired", null,
                "Execution leases whose holder stopped renewing them",
                () -> reliability.value("expiredExecutionLeases"));

        Cached activeLeases = new Cached(properties.metrics().databaseGaugeCacheMs(),
                () -> jobRepository.countActiveLeases(Instant.now()));
        gauge(registry, "distroq.execution_leases.active", null,
                "Execution leases currently held and unexpired", activeLeases::get);

        gauge(registry, "distroq.worker.active", null,
                "Job executions running in this process", workerMetrics::activeWorkers);
        gauge(registry, "distroq.worker.concurrency", null,
                "Configured worker concurrency for this process", workerMetrics::concurrency);

        FunctionCounter.builder("distroq.reconciliation.repairs", reliability,
                        source -> source.value("reconciliationRepairs"))
                .description("Repairs recorded in the reliability audit trail")
                .register(registry);
        FunctionCounter.builder("distroq.effect.deduplication.hits", effectMetrics,
                        EffectMetrics::deduplicationHits)
                .description("Effect applications short-circuited by the ledger, this process only")
                .register(registry);
        FunctionCounter.builder("distroq.effect.applications", reliability,
                        source -> source.value("effectApplications"))
                .description("Completed effect ledger entries")
                .register(registry);
    }

    private static void gauge(MeterRegistry registry, String name, String unit, String description,
                              java.util.function.Supplier<Number> supplier) {
        Gauge.builder(name, supplier)
                .description(description)
                .baseUnit(unit)
                .strongReference(true)
                .register(registry);
    }

    /** A count that is allowed to be a few seconds old, so a scrape is not a burst of queries. */
    private static final class Cached {

        private final long maxAgeMs;
        private final LongSupplier source;
        private final AtomicLong value = new AtomicLong();
        private final AtomicLong takenAtMs = new AtomicLong();

        private Cached(long maxAgeMs, LongSupplier source) {
            this.maxAgeMs = Math.max(0, maxAgeMs);
            this.source = source;
        }

        double get() {
            long now = System.currentTimeMillis();
            if (now - takenAtMs.get() >= maxAgeMs) {
                try {
                    value.set(source.getAsLong());
                    takenAtMs.set(now);
                } catch (Exception e) {
                    log.debug("Could not refresh a database-derived gauge; serving the last value", e);
                }
            }
            return value.get();
        }
    }
}
