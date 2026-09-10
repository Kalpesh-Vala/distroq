package com.distroq.queue;

import com.distroq.config.DistroqProperties;
import com.distroq.lifecycle.ShutdownState;
import com.distroq.metrics.DistroqMetrics;
import com.distroq.model.Priority;
import com.distroq.observability.Events;
import com.distroq.observability.LogContext;
import com.distroq.worker.WorkerMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Picks up work abandoned by a worker that never came back.
 *
 * <p>This is the capability a Redis List could not provide at all. A popped list element is simply
 * gone — Redis has no record that anyone was holding it, so a worker that died mid-job took the
 * job with it and left a row stuck at RUNNING forever. A stream entry stays in the group's Pending
 * Entries List, with an owner and an idle timer, until it is acknowledged.
 *
 * <p>{@code XAUTOCLAIM} scans that list for entries idle longer than
 * {@code distroq.streams.claim-min-idle-ms} and transfers them to this consumer in one command.
 * The older {@code XPENDING}-then-{@code XCLAIM} pair would work, but it is two round trips with a
 * race in the middle; lettuce-core 6.6.0 binds {@code XAUTOCLAIM} directly, so there is no reason
 * to use the fallback.
 *
 * <p><b>Idle is not death.</b> Redis measures time since delivery, not liveness. An entry held by a
 * healthy worker running a job longer than the threshold looks exactly like one held by a corpse,
 * and will be reclaimed and executed a second time. {@code claim-min-idle-ms} must therefore
 * exceed the longest expected job duration. Entries held by <em>this</em> process are the one case
 * that can be told apart, and {@link DeliveryHandler#isInFlight} does so.
 */
@Component
public class PendingEntryRecovery {

    private static final Logger log = LoggerFactory.getLogger(PendingEntryRecovery.class);

    /** XAUTOCLAIM's "start from the beginning of the pending list" cursor, and its "done" reply. */
    private static final String SCAN_START = "0-0";

    /** Bounds one sweep, so a large pending list cannot monopolise the scheduler thread. */
    private static final int MAX_BATCHES_PER_SWEEP = 10;

    private final JobStreamConsumer consumer;
    private final DeliveryHandler handler;
    private final StreamKeys streamKeys;
    private final StringRedisTemplate redis;
    private final String groupName;
    private final String recoveryConsumerName;
    private final Duration minIdle;
    private final int batchSize;
    private final WorkerMetrics workerMetrics;
    private final DistroqMetrics metrics;
    private final ShutdownState shutdownState;

    public PendingEntryRecovery(JobStreamConsumer consumer,
                                DeliveryHandler handler,
                                StreamKeys streamKeys,
                                StringRedisTemplate redis,
                                WorkerMetrics workerMetrics,
                                DistroqMetrics metrics,
                                ShutdownState shutdownState,
                                DistroqProperties properties) {
        this.consumer = consumer;
        this.handler = handler;
        this.streamKeys = streamKeys;
        this.redis = redis;
        this.workerMetrics = workerMetrics;
        this.metrics = metrics;
        this.shutdownState = shutdownState;
        this.groupName = properties.streams().groupName();
        this.recoveryConsumerName = consumer.newConsumerName();
        this.minIdle = Duration.ofMillis(properties.streams().claimMinIdleMs());
        this.batchSize = Math.max(1, properties.streams().claimBatchSize());
    }

    @Scheduled(fixedDelayString = "${distroq.streams.recovery-interval-ms:1000}")
    public void sweep() {
        if (!shutdownState.isRunning()) {
            return;
        }
        for (Priority tier : Priority.STRICT_ORDER) {
            try {
                reclaim(tier);
            } catch (Exception e) {
                // never propagate: an escaping exception cancels all future executions of this task
                if (!shutdownState.isRunning()) {
                    log.debug("Recovery sweep aborted during shutdown");
                    return;
                }
                log.error("Recovery sweep of the {} stream failed, retrying on the next tick", tier, e);
            }
        }
    }

    private void reclaim(Priority tier) {
        String streamKey = streamKeys.keyFor(tier);
        String cursor = SCAN_START;

        for (int batch = 0; batch < MAX_BATCHES_PER_SWEEP && shutdownState.isRunning(); batch++) {
            // read the current owners first: XAUTOCLAIM reassigns before it answers, so afterwards
            // every entry claims to belong to us and the log could not name who lost it
            Map<String, PendingMessage> before = ownersOf(streamKey);

            JobStreamConsumer.ClaimedBatch claimed =
                    consumer.claimStale(recoveryConsumerName, tier, minIdle, batchSize, cursor);
            if (claimed.claimed() > 0) {
                workerMetrics.reclaimed(claimed.claimed());
                metrics.jobsReclaimed(tier, claimed.claimed());
                log.warn("XAUTOCLAIM on {} took {} idle entr(ies) for {} (cursor {} -> {})",
                    streamKey, claimed.claimed(), recoveryConsumerName, cursor, claimed.nextCursor());
            }

            for (StreamDelivery delivery : claimed.deliveries()) {
                if (handler.isInFlight(delivery.streamKey(), delivery.entryId())) {
                    // our own long-running job: ownership is unchanged, only the idle clock reset
                    log.debug("Entry {} on {} is still running here; not treating it as abandoned",
                            delivery.entryId(), streamKey);
                    continue;
                }
                PendingMessage previous = before.get(delivery.entryId());
                try (LogContext ignored = LogContext.event(Events.JOB_RECLAIMED)
                        .job(delivery.jobId()).priority(tier)
                        .stream(streamKey, delivery.entryId())
                        .consumer(recoveryConsumerName)) {
                    log.warn("Reclaimed entry {} on {} for job {}: previous owner {}, new owner {}, "
                                    + "idle {}ms, delivery count {}",
                            delivery.entryId(), streamKey, delivery.jobId(),
                            previous == null ? "unknown" : previous.getConsumerName(),
                            recoveryConsumerName,
                            previous == null ? -1 : previous.getElapsedTimeSinceLastDelivery().toMillis(),
                            previous == null ? -1 : previous.getTotalDeliveryCount());
                }
                handler.handle(delivery, recoveryConsumerName);
            }

            // 0-0 means the scan wrapped; a short batch means there was nothing more to take
            if (SCAN_START.equals(claimed.nextCursor()) || claimed.claimed() < batchSize) {
                return;
            }
            cursor = claimed.nextCursor();
        }
    }

    /** Current pending entries on a stream, keyed by entry ID, for logging the ownership change. */
    private Map<String, PendingMessage> ownersOf(String streamKey) {
        try {
            StreamOperations<String, String, String> streamOps = redis.opsForStream();
            PendingMessages pending =
                    streamOps.pending(streamKey, groupName, Range.unbounded(), batchSize);
            if (pending == null || pending.isEmpty()) {
                return Map.of();
            }
            Map<String, PendingMessage> owners = new LinkedHashMap<>();
            for (PendingMessage message : pending) {
                owners.put(message.getIdAsString(), message);
            }
            return owners;
        } catch (Exception e) {
            log.debug("Could not read pending owners for {}; reclaim logging will be partial",
                    streamKey, e);
            return Map.of();
        }
    }
}
