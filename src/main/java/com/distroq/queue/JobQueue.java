package com.distroq.queue;

import com.distroq.config.DistroqProperties;
import com.distroq.model.Priority;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Write side of the queue: one Redis Stream per {@link Priority} tier, plus the single delayed
 * sorted set that feeds them.
 *
 * <p>v0.5 replaced the pending lists with streams. A list pop is destructive — once an ID is out,
 * Redis has no record that anyone was holding it — so a worker that died mid-job took the job with
 * it. A stream entry stays in the group's Pending Entries List until {@code XACK}, which is what
 * makes {@link PendingEntryRecovery} possible at all.
 *
 * <p>No {@code MAXLEN} trimming: a trimmed entry that is still pending cannot be inspected or
 * reclaimed, and inspection is the whole point of the change. Streams therefore grow without
 * bound in v0.5, which is a real operational limitation rather than an oversight.
 *
 * <p>Reading is deliberately not here — see {@link JobStreamConsumer}. Nothing in this class
 * touches the v0.4 list keys; the only code that still does is the one-time migration in
 * {@link JobStreamInitializer}.
 */
@Component
public class JobQueue {

    private static final Logger log = LoggerFactory.getLogger(JobQueue.class);

    private final StringRedisTemplate redis;
    private final StreamKeys streamKeys;
    private final LettuceStreamCommands lettuce;
    private final DueSetPromoter promoter;
    private final String delayedKey;
    private final String groupName;

    public JobQueue(StringRedisTemplate redis,
                    StreamKeys streamKeys,
                    LettuceStreamCommands lettuce,
                    DueSetPromoter promoter,
                    DistroqProperties properties) {
        this.redis = redis;
        this.streamKeys = streamKeys;
        this.lettuce = lettuce;
        this.promoter = promoter;
        this.delayedKey = properties.delayedKey();
        this.groupName = properties.streams().groupName();
    }

    /**
     * {@code XADD} the job onto its tier's stream.
     *
     * @return the Redis-generated entry ID, which is the handle every later operation on this
     *         delivery — acknowledgement, pending inspection, reclaim — is addressed by
     */
    public String enqueue(UUID jobId, Priority priority, EnqueueSource source) {
        JobStreamEntry entry = JobStreamEntry.now(jobId, priority, source);
        String key = streamKeys.keyFor(entry.priority());
        RecordId id = streamOps().add(key, entry.toFields());
        String entryId = id == null ? null : id.getValue();
        log.debug("XADD job {} ({}, {}) onto {} as {}",
                jobId, entry.priority(), entry.source(), key, entryId);
        return entryId;
    }

    /**
     * Park a job until {@code dueAt}, remembering its tier.
     *
     * <p>The member is {@code <TIER>:<uuid>} rather than a bare UUID, unchanged from v0.4. The
     * sorted set is one key with no tier of its own, so the promotion has to learn the tier from
     * somewhere; encoding it in the member keeps range + ZREM + XADD inside a single atomic
     * script, where a database lookup per promoted job would not.
     *
     * <p>ZADD still overwrites the score of a member already present, so re-scheduling the same
     * job is idempotent — which is what stops a redelivered stream entry producing a second,
     * competing retry.
     */
    public void scheduleAt(UUID jobId, Priority priority, Instant dueAt) {
        redis.opsForZSet().add(delayedKey, SortedSetMember.encode(jobId, priority), dueAt.toEpochMilli());
        log.debug("Scheduled job {} ({}) on {} for {}", jobId, priority, delayedKey, dueAt);
    }

    /**
     * Move up to {@code limit} jobs whose due time has passed onto their own tier's stream.
     *
     * @return how many were moved
     */
    public int promoteDueJobs(Instant now, int limit) {
        return promoter.promote(delayedKey, now, limit, EnqueueSource.RETRY);
    }

    public long delayedDepth() {
        Long size = redis.opsForZSet().zCard(delayedKey);
        return size == null ? 0L : size;
    }

    /**
     * {@code XLEN} per tier: every entry the stream has ever been given, acknowledged or not.
     * This is history, not backlog. See {@link #readyDepthByPriority()}.
     */
    public Map<Priority, Long> streamDepthByPriority() {
        Map<Priority, Long> depths = new LinkedHashMap<>();
        for (Priority priority : Priority.STRICT_ORDER) {
            Long size = streamOps().size(streamKeys.keyFor(priority));
            depths.put(priority, size == null ? 0L : size);
        }
        return depths;
    }

    /**
     * Entries the consumer group has never been offered — the successor to v0.4's list depth, and
     * the only one of the three stream counts that answers "how much work is waiting".
     *
     * <p>Taken from the Redis 7 {@code lag} field of {@code XINFO GROUPS}, not derived from
     * {@code XLEN}. A null lag is reported as zero and logged; see {@link LettuceStreamCommands}.
     */
    public Map<Priority, Long> readyDepthByPriority() {
        Map<Priority, Long> depths = new LinkedHashMap<>();
        for (Priority priority : Priority.STRICT_ORDER) {
            String key = streamKeys.keyFor(priority);
            Long lag = lettuce.groupLag(key, groupName);
            if (lag == null) {
                log.debug("{} reports no lag for group {}; reporting 0", key, groupName);
            }
            depths.put(priority, lag == null ? 0L : lag);
        }
        return depths;
    }

    public long readyDepth() {
        return readyDepthByPriority().values().stream().mapToLong(Long::longValue).sum();
    }

    /** Entries delivered to a consumer in the group and not yet acknowledged, per tier. */
    public Map<Priority, Long> pendingEntriesByPriority() {
        Map<Priority, Long> pending = new LinkedHashMap<>();
        for (Priority priority : Priority.STRICT_ORDER) {
            PendingMessagesSummary summary = streamOps().pending(streamKeys.keyFor(priority), groupName);
            pending.put(priority, summary == null ? 0L : summary.getTotalPendingMessages());
        }
        return pending;
    }

    /**
     * Consumer names appearing in any tier's pending summary.
     *
     * <p>This is "consumers currently holding work", not "consumers registered in the group": an
     * idle worker holds nothing and so does not appear, and a consumer that died holding an entry
     * does appear until that entry is reclaimed.
     */
    public Set<String> consumersHoldingEntries() {
        Set<String> consumers = new LinkedHashSet<>();
        for (Priority priority : Priority.STRICT_ORDER) {
            PendingMessagesSummary summary = streamOps().pending(streamKeys.keyFor(priority), groupName);
            if (summary != null) {
                consumers.addAll(summary.getPendingMessagesPerConsumer().keySet());
            }
        }
        return consumers;
    }

    private StreamOperations<String, String, String> streamOps() {
        return redis.opsForStream();
    }
}
