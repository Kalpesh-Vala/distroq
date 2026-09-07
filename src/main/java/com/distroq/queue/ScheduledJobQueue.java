package com.distroq.queue;

import com.distroq.config.DistroqProperties;
import com.distroq.model.Priority;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Durable storage for jobs waiting on a user-requested execution time.
 *
 * <p>A second sorted set, {@code distroq:jobs:scheduled}, deliberately not the v0.4 retry set. The
 * two answer different questions — "the operator asked for this at 15:30" versus "attempt 2 failed
 * and attempt 3 is due in 4 seconds" — and merging them would make both metrics meaningless and
 * tie the promotion behaviour of one to the other forever. The mechanism is identical and shared
 * via {@link DueSetPromoter}; only the key, the source label and the meaning differ.
 *
 * <p>Redis, not an in-process timer. A {@code Thread.sleep} or a {@code ScheduledExecutorService}
 * entry dies with the process; a sorted-set member does not, which is the whole of v0.6's restart
 * durability claim.
 *
 * <p>The Redis connection and the stream key mapping are not configured here — they come from the
 * same {@link StringRedisTemplate} and {@link StreamKeys} everything else uses.
 */
@Component
public class ScheduledJobQueue {

    private static final Logger log = LoggerFactory.getLogger(ScheduledJobQueue.class);

    private final StringRedisTemplate redis;
    private final DueSetPromoter promoter;
    private final String scheduledKey;

    public ScheduledJobQueue(StringRedisTemplate redis,
                             DueSetPromoter promoter,
                             DistroqProperties properties) {
        this.redis = redis;
        this.promoter = promoter;
        this.scheduledKey = properties.scheduledKey();
    }

    public String key() {
        return scheduledKey;
    }

    /**
     * Park a job until {@code scheduledAt}, remembering its tier.
     *
     * <p>{@code ZADD NX}, not plain {@code ZADD}. Rescheduling is out of scope in v0.6, so a
     * second call for a job already in the set is an idempotent no-op that leaves the original
     * score untouched — it does not move the due time and it does not create a second member.
     * Plain {@code ZADD} would overwrite the score, which is exactly right for the retry set (a
     * new backoff supersedes the old one) and exactly wrong here, where the only thing that can
     * produce a duplicate call is a repeat of a request that has already been accepted. Silently
     * moving a job's execution time because a submission was retried at the HTTP layer is the
     * worse failure of the two.
     *
     * <p>Not rejected as an error either: the caller cannot tell a duplicate from a first
     * submission without a round trip it does not otherwise need, and the outcome the caller
     * wanted — "this job is in the set, due at this time" — is true afterwards in both cases. The
     * no-op is logged so it is visible rather than invisible.
     */
    public void schedule(UUID jobId, Priority priority, Instant scheduledAt) {
        String member = SortedSetMember.encode(jobId, priority);
        Boolean added = redis.opsForZSet().addIfAbsent(scheduledKey, member, scheduledAt.toEpochMilli());
        if (Boolean.FALSE.equals(added)) {
            log.warn("Job {} ({}) is already on {}; leaving its existing due time alone",
                    jobId, priority, scheduledKey);
            return;
        }
        log.debug("Scheduled job {} ({}) on {} for {} ({})",
                jobId, priority, scheduledKey, scheduledAt, scheduledAt.toEpochMilli());
    }

    /**
     * Move up to {@code limit} jobs whose requested time has arrived onto their own tier's stream,
     * stamped {@code source=SCHEDULED}.
     *
     * @return how many were moved
     */
    public int promoteDueJobs(Instant now, int limit) {
        return promoter.promote(scheduledKey, now, limit, EnqueueSource.SCHEDULED);
    }

    /** Total members waiting on a requested execution time. Not retry depth, not stream depth. */
    public long scheduledDepth() {
        Long size = redis.opsForZSet().zCard(scheduledKey);
        return size == null ? 0L : size;
    }

    /**
     * Scheduled members grouped by the tier encoded in the member.
     *
     * <p>{@code ZRANGE 0 -1} and count, which is O(N) in the size of the set. There is no Redis
     * command that groups a sorted set by a prefix of its members, and the alternatives — three
     * sets, or a companion hash — would each add a write that is not atomic with the {@code ZADD}
     * that matters. The set holds one member per waiting job, so this is bounded by outstanding
     * scheduled work rather than by history; it is a real cost at scale and named as such in
     * README.md rather than hidden.
     *
     * <p>Every tier appears, including the ones at zero, so the shape of the response does not
     * change as jobs come and go. A member that does not parse is counted nowhere and logged.
     */
    public Map<Priority, Long> scheduledDepthByPriority() {
        // LinkedHashMap seeded with every tier, so the response shape is stable in declaration
        // order whether or not any given tier currently has work waiting
        Map<Priority, Long> depths = new LinkedHashMap<>();
        for (Priority priority : Priority.STRICT_ORDER) {
            depths.put(priority, 0L);
        }

        Set<String> members = redis.opsForZSet().range(scheduledKey, 0, -1);
        if (members != null) {
            for (String raw : members) {
                SortedSetMember.parse(raw).ifPresentOrElse(
                        parsed -> depths.merge(parsed.priority(), 1L, Long::sum),
                        () -> log.warn("Unreadable member '{}' on {}; excluded from the scheduled "
                                + "depth breakdown", raw, scheduledKey));
            }
        }
        return depths;
    }
}
