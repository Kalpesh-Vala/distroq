package com.distroq.reliability;

import com.distroq.config.DistroqProperties;
import com.distroq.effects.EffectMetrics;
import com.distroq.model.EffectStatus;
import com.distroq.model.OutboxStatus;
import com.distroq.model.ReliabilityActionType;
import com.distroq.repository.JobEffectRepository;
import com.distroq.repository.JobRepository;
import com.distroq.repository.OutboxEventRepository;
import com.distroq.repository.ReliabilityActionRepository;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The v0.8 half of {@code /api/metrics}.
 *
 * <p>Everything here except {@code effectDeduplicationHits} is a live database count rather than
 * a remembered number, which costs a handful of indexed aggregates per call and buys two things:
 * the values do not drift after a restart, and they are the same on every instance. A cached
 * gauge from the last reconciliation run would have been cheaper and would have reported an
 * outage that had already been fixed, or missed one that started thirty seconds ago.
 *
 * <p>{@code reconciliationFindings} is a recount of the same predicates reconciliation uses, not
 * the size of its last report. It is therefore current, and it deliberately counts only the
 * findings that can be expressed as a single indexed predicate — the per-job event cross-checks
 * are too expensive to run on every metrics scrape and appear only in the reconciliation report.
 */
@Component
public class ReliabilityMetrics {

    private final OutboxEventRepository outboxEvents;
    private final JobRepository jobs;
    private final JobEffectRepository effects;
    private final ReliabilityActionRepository actions;
    private final EffectMetrics effectMetrics;
    private final DistroqProperties.Reconciliation reconciliation;
    private final DistroqProperties.Effects effectProperties;

    public ReliabilityMetrics(OutboxEventRepository outboxEvents,
                              JobRepository jobs,
                              JobEffectRepository effects,
                              ReliabilityActionRepository actions,
                              EffectMetrics effectMetrics,
                              DistroqProperties properties) {
        this.outboxEvents = outboxEvents;
        this.jobs = jobs;
        this.effects = effects;
        this.actions = actions;
        this.effectMetrics = effectMetrics;
        this.reconciliation = properties.reconciliation();
        this.effectProperties = properties.effects();
    }

    public Map<String, Object> snapshot(Instant now) {
        Instant staleOutbox = now.minusMillis(reconciliation.staleOutboxAfterMs());
        Instant staleScheduled = now.minusMillis(reconciliation.staleScheduledAfterMs());
        Instant staleLease = now.minusMillis(reconciliation.staleLeaseAfterMs());
        Instant staleEffect = now.minusMillis(effectProperties.staleStartedAfterMs());

        long stalePending = outboxEvents.countStalePending(staleOutbox);
        long expiredLocks = outboxEvents.countExpiredLocks(now);
        long terminalFailed = outboxEvents.countByStatus(OutboxStatus.FAILED);
        long staleScheduledJobs = jobs.countStaleScheduled(staleScheduled);
        long staleRetryJobs = jobs.countStaleRetrying(staleScheduled);
        long expiredLeases = jobs.countExpiredLeases(staleLease);
        long staleEffects = effects.countStaleStarted(staleEffect);

        Instant oldest = outboxEvents.oldestUnpublishedCreatedAt();

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("outboxPending", outboxEvents.countPending(now));
        metrics.put("outboxRetryableFailed", outboxEvents.countRetryableFailures());
        metrics.put("outboxTerminalFailed", terminalFailed);
        metrics.put("outboxOldestAgeMs", oldest == null ? 0L
                : Math.max(0L, Duration.between(oldest, now).toMillis()));
        metrics.put("outboxPublishedTotal", outboxEvents.countByPublishedAtIsNotNull());
        metrics.put("outboxCleanupDeleted",
                actions.countByActionType(ReliabilityActionType.OUTBOX_CLEANUP));
        metrics.put("reconciliationFindings", stalePending + expiredLocks + terminalFailed
                + staleScheduledJobs + staleRetryJobs + expiredLeases + staleEffects);
        metrics.put("reconciliationRepairs",
                actions.countByActionTypeNot(ReliabilityActionType.OUTBOX_CLEANUP));
        metrics.put("staleScheduledJobs", staleScheduledJobs);
        metrics.put("staleRetryJobs", staleRetryJobs);
        metrics.put("expiredExecutionLeases", expiredLeases);
        metrics.put("staleEffects", staleEffects);
        metrics.put("effectDeduplicationHits", effectMetrics.deduplicationHits());
        metrics.put("effectApplications", effects.countByStatus(EffectStatus.COMPLETED));
        return metrics;
    }
}
