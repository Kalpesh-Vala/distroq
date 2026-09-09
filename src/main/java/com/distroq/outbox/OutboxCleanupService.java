package com.distroq.outbox;

import com.distroq.config.DistroqProperties;
import com.distroq.model.OutboxEvent;
import com.distroq.model.ReliabilityActionType;
import com.distroq.reliability.ReliabilityAuditService;
import com.distroq.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Retention for the outbox table.
 *
 * <p>Only PUBLISHED rows are ever deleted, and only once two clocks have both run out: the
 * configured retention window, and the Redis deduplication marker's TTL. The second one matters
 * more than it looks. While the marker is alive, republishing an old event is a no-op, so the row
 * and the marker are redundant with each other and losing either is survivable. Once the marker
 * has expired the row is the last remaining evidence that the publication happened, and deleting
 * it while it is still inside the window in which something might re-publish is how a duplicate
 * gets made. Cleanup therefore waits for the longer of the two. See NOTES.md.
 *
 * <p>PENDING, PUBLISHING and FAILED are never deleted by anything in v0.8.
 */
@Service
public class OutboxCleanupService {

    private static final Logger log = LoggerFactory.getLogger(OutboxCleanupService.class);

    private final OutboxEventRepository repository;
    private final ReliabilityAuditService audit;
    private final DistroqProperties.Outbox outbox;
    private final DistroqProperties.Reconciliation reconciliation;

    public OutboxCleanupService(OutboxEventRepository repository, ReliabilityAuditService audit,
                                DistroqProperties properties) {
        this.repository = repository;
        this.audit = audit;
        this.outbox = properties.outbox();
        this.reconciliation = properties.reconciliation();
    }

    @Scheduled(fixedDelayString = "${distroq.outbox.cleanup-interval-ms:3600000}",
            initialDelayString = "${distroq.outbox.cleanup-interval-ms:3600000}")
    public void scheduledCleanup() {
        CleanupResult result = cleanup("Scheduled outbox retention sweep",
                ReliabilityAuditService.SYSTEM_ACTOR);
        if (result.deleted() > 0) {
            log.info("Outbox retention deleted {} published event(s) older than {}",
                    result.deleted(), result.cutoff());
        }
    }

    @Transactional
    public CleanupResult cleanup(String reason, String actor) {
        Instant now = Instant.now();
        Instant cutoff = now.minus(retentionWindow());
        Instant staleBefore = now.minusMillis(Math.max(
                Math.max(reconciliation.staleOutboxAfterMs(), reconciliation.staleScheduledAfterMs()),
                reconciliation.staleLeaseAfterMs()));

        List<OutboxEvent> deletable =
                repository.deletablePublished(cutoff, staleBefore, outbox.cleanupBatchSize());
        for (OutboxEvent event : deletable) {
            audit.record(ReliabilityActionType.OUTBOX_CLEANUP, "OutboxEvent", event.getId(),
                    reason, actor, OutboxOperatorService.describe(event), "deleted");
        }
        repository.deleteAll(deletable);
        return new CleanupResult(deletable.size(), outbox.cleanupBatchSize(), cutoff,
                deletable.size() == outbox.cleanupBatchSize());
    }

    /**
     * The longer of the retention window and the deduplication marker TTL. Configuring a short
     * retention does not shorten this — it only means the marker is now the binding constraint.
     */
    public Duration retentionWindow() {
        Duration configured = Duration.ofDays(Math.max(0, outbox.publishedRetentionDays()));
        Duration dedupe = Duration.ofMillis(Math.max(0, outbox.dedupeRetentionMs()));
        return configured.compareTo(dedupe) >= 0 ? configured : dedupe;
    }

    public record CleanupResult(int deleted, int batchSize, Instant cutoff, boolean batchFull) {
    }
}
