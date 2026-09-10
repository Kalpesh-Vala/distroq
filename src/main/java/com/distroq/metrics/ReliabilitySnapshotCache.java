package com.distroq.metrics;

import com.distroq.config.DistroqProperties;
import com.distroq.reliability.ReliabilityMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One reliability snapshot shared by every gauge that needs it.
 *
 * <p>{@link ReliabilityMetrics} answers with live database aggregates, which is the right choice
 * for a hand-run {@code /api/metrics} call and the wrong one for a scrape: nine gauges times one
 * scrape every fifteen seconds times every instance is a lot of counting, and a Prometheus
 * registry reads all of its gauges at once anyway, so they would all be answering the same
 * question a millisecond apart.
 *
 * <p>A failed refresh keeps the previous values rather than reporting zero. Zero pending outbox
 * events is a normal, healthy reading, and publishing it because PostgreSQL is unreachable would
 * silence exactly the alert that should be firing — the database being down is what readiness and
 * the {@code db} health indicator are for.
 */
@Component
public class ReliabilitySnapshotCache {

    private static final Logger log = LoggerFactory.getLogger(ReliabilitySnapshotCache.class);

    private final ReliabilityMetrics source;
    private final long maxAgeMs;
    private final AtomicReference<Snapshot> cached = new AtomicReference<>(Snapshot.empty());

    public ReliabilitySnapshotCache(ReliabilityMetrics source, DistroqProperties properties) {
        this.source = source;
        this.maxAgeMs = Math.max(0, properties.metrics().databaseGaugeCacheMs());
    }

    public double value(String key) {
        Object value = current().values().get(key);
        return value instanceof Number number ? number.doubleValue() : Double.NaN;
    }

    private Snapshot current() {
        Snapshot snapshot = cached.get();
        long now = System.currentTimeMillis();
        if (now - snapshot.takenAtMs() < maxAgeMs && !snapshot.values().isEmpty()) {
            return snapshot;
        }
        try {
            Snapshot refreshed = new Snapshot(now, source.snapshot(Instant.now()));
            cached.set(refreshed);
            return refreshed;
        } catch (Exception e) {
            log.debug("Could not refresh reliability gauges; serving the previous values", e);
            return snapshot;
        }
    }

    private record Snapshot(long takenAtMs, Map<String, Object> values) {
        static Snapshot empty() {
            return new Snapshot(0L, Map.of());
        }
    }
}
