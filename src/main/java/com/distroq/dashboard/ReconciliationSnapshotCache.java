package com.distroq.dashboard;

import com.distroq.reliability.ReconciliationReport;
import com.distroq.reliability.ReconciliationService;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One reconciliation preview, reused until it goes stale.
 *
 * <p>A preview is not free. It takes a PostgreSQL advisory lock, runs a batch of indexed scans
 * across five categories and cross-checks outbox events per job. That is the right cost for an
 * operator pressing a button; it is the wrong cost for a browser polling every five seconds, and
 * multiplying it by every open tab would turn a monitoring page into a load generator.
 *
 * <p>It is safe to reuse. The preview is called with {@code requestedAutoRepair = false}, which
 * makes every finding {@code REPORTED} and performs no repair and therefore no audit write — so
 * this is a read, and a slightly old read of a condition measured in minutes loses nothing. The
 * age of the answer is returned alongside it and rendered as a last-updated time rather than
 * presented as live.
 *
 * <p>{@code waitForLock = false}: a dashboard poll that arrives while another instance is
 * reconciling should say so, not queue behind it.
 */
@Component
public class ReconciliationSnapshotCache {

    static final String PREVIEW_REASON = "Dashboard read-only preview";

    private final ReconciliationService reconciliation;
    private final Duration ttl;
    private final AtomicReference<Snapshot> cached = new AtomicReference<>();

    public ReconciliationSnapshotCache(ReconciliationService reconciliation,
                                       DashboardProperties properties) {
        this.reconciliation = reconciliation;
        this.ttl = Duration.ofMillis(Math.max(0, properties.reconciliationCacheMs()));
    }

    public record Snapshot(ReconciliationReport report, Instant generatedAt) {
    }

    public Snapshot get() {
        Instant now = Instant.now();
        Snapshot existing = cached.get();
        if (existing != null && Duration.between(existing.generatedAt(), now).compareTo(ttl) < 0) {
            return existing;
        }
        Snapshot refreshed = new Snapshot(
                reconciliation.run(false, PREVIEW_REASON, "dashboard", false), Instant.now());
        cached.set(refreshed);
        return refreshed;
    }
}
