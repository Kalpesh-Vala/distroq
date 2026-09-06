package com.distroq.queue;

import com.distroq.config.DistroqProperties;
import com.distroq.model.Priority;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Redis holds job IDs only; PostgreSQL remains the source of truth.
 *
 * <p>Structures: one pending list per {@link Priority} tier, and a single sorted set of delayed
 * IDs scored by the epoch-millis instant at which they become eligible.
 *
 * <p>The sorted set is deliberately <em>not</em> split per tier. Splitting it would mean three
 * sweeps per poll tick instead of one and would still not answer "what is due next" across tiers
 * without merging client-side. Instead the member itself carries the tier — see
 * {@link #scheduleAt}.
 */
@Component
public class JobQueue {

    private static final Logger log = LoggerFactory.getLogger(JobQueue.class);

    /** Separates the tier from the UUID in a delayed-set member: {@code HIGH:<uuid>}. */
    private static final char TIER_SEPARATOR = ':';

    /**
     * Range + remove + push in one server-side round trip, so no ID can be promoted twice.
     *
     * <p>v0.4 change: the destination is chosen per member rather than being one fixed key.
     * KEYS[2..] are the tier lists in {@link Priority} declaration order, ARGV[3..] the matching
     * tier names, and the final ARGV the default tier's name — so the script never builds a key
     * name itself. A member with no separator is a v0.3 leftover (bare UUID, no tier) and goes to
     * the default tier.
     */
    private static final String PROMOTE_LUA = """
            local due = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1], 'LIMIT', 0, ARGV[2])
            local dest = {}
            for i = 2, #KEYS do
              dest[ARGV[i + 1]] = KEYS[i]
            end
            local fallback = dest[ARGV[#ARGV]]
            local moved = 0
            for i = 1, #due do
              local member = due[i]
              if redis.call('ZREM', KEYS[1], member) == 1 then
                local sep = string.find(member, ':', 1, true)
                local id = member
                local target = fallback
                if sep then
                  local tier = string.sub(member, 1, sep - 1)
                  id = string.sub(member, sep + 1)
                  if dest[tier] then
                    target = dest[tier]
                  end
                end
                redis.call('LPUSH', target, id)
                moved = moved + 1
              end
            end
            return moved
            """;

    private final StringRedisTemplate redis;
    private final String legacyQueueKey;
    private final String delayedKey;
    private final Map<Priority, String> tierKeys;
    private final RedisScript<Long> promoteScript;

    public JobQueue(StringRedisTemplate redis, DistroqProperties properties) {
        this.redis = redis;
        this.legacyQueueKey = properties.queueKey();
        this.delayedKey = properties.delayedKey();
        this.promoteScript = new DefaultRedisScript<>(PROMOTE_LUA, Long.class);

        // derived from the one configured base rather than three separate config entries, so the
        // key set cannot drift out of sync with the enum
        Map<Priority, String> keys = new EnumMap<>(Priority.class);
        for (Priority priority : Priority.values()) {
            keys.put(priority, properties.queueKey() + TIER_SEPARATOR + priority.keySuffix());
        }
        this.tierKeys = Map.copyOf(keys);
    }

    /**
     * Move anything left in the v0.3 single pending key onto the default tier.
     *
     * <p>The alternative was to document "drain the queue before upgrading", which turns silently
     * stranded work into a documentation problem rather than a code problem. Those IDs are
     * committed jobs sitting in Postgres as QUEUED; no worker would ever read the old key again,
     * so they would join the invisible-forever category in NOTES.md.
     *
     * <p>{@code RPOPLPUSH} per item is atomic per item and preserves FIFO order: it takes the
     * oldest entry (tail) of the old key and pushes it to the head of the target, so the first
     * drained ends up nearest the tail — exactly where the next {@code BRPOP} looks.
     *
     * <p>Runs in {@code @PostConstruct} rather than on {@code ApplicationReadyEvent} because
     * {@code Worker} depends on this bean, so its own {@code @PostConstruct} — and therefore the
     * first {@code BRPOP} — cannot start until this has returned. Failures are logged, not
     * thrown: an unreachable Redis has never prevented this application from starting, and this
     * is not the version to change that.
     */
    @PostConstruct
    void drainLegacyPendingKey() {
        String target = keyFor(Priority.DEFAULT);
        try {
            long drained = 0;
            while (redis.opsForList().rightPopAndLeftPush(legacyQueueKey, target) != null) {
                drained++;
            }
            if (drained > 0) {
                log.warn("Drained {} job ID(s) left in the v0.3 key {} onto {}",
                        drained, legacyQueueKey, target);
            } else {
                log.debug("Nothing to drain from the v0.3 key {}", legacyQueueKey);
            }
        } catch (Exception e) {
            log.error("Could not drain the v0.3 key {}; any IDs still in it will not be executed",
                    legacyQueueKey, e);
        }
    }

    public void enqueue(UUID jobId, Priority priority) {
        String key = keyFor(priority);
        redis.opsForList().leftPush(key, jobId.toString());
        log.debug("Enqueued job {} onto {}", jobId, key);
    }

    /**
     * Blocking multi-key {@code BRPOP} over the given tiers, in the order given. Returns null when
     * the timeout elapses with every one of them empty.
     *
     * <p>{@code BRPOP} returns from the first non-empty key in the order supplied and blocks until
     * any of them has data, so priority order and blocking semantics come out of a single atomic
     * call. No polling, and no window between deciding which tier to read and reading it.
     *
     * <p>Spring Data Redis 3.5.13 has no multi-key overload on {@code ListOperations} — every
     * {@code rightPop} there takes one key — so this drops to the connection-level binding
     * {@code RedisListCommands.bRPop(int, byte[]...)}, which is the same method
     * {@code DefaultListOperations.rightPop(K, Duration)} delegates to with a single key.
     */
    public Dequeued dequeue(List<Priority> order, Duration timeout) {
        List<Priority> tiers = order.isEmpty() ? Priority.STRICT_ORDER : order;
        byte[][] keys = new byte[tiers.size()][];
        for (int i = 0; i < tiers.size(); i++) {
            keys[i] = keyFor(tiers.get(i)).getBytes(StandardCharsets.UTF_8);
        }

        // BRPOP's timeout is whole seconds, and 0 means "block forever" — which would never
        // release the loop to notice a shutdown
        int timeoutSeconds = (int) Math.max(1L, timeout.toSeconds());

        List<byte[]> popped = redis.execute(
                (RedisCallback<List<byte[]>>) (RedisConnection connection) ->
                        connection.listCommands().bRPop(timeoutSeconds, keys));

        if (popped == null || popped.size() < 2) {
            return null;
        }

        // BRPOP replies with [key, value], which is how the caller learns which tier served it
        String key = new String(popped.get(0), StandardCharsets.UTF_8);
        String raw = new String(popped.get(1), StandardCharsets.UTF_8);

        try {
            return new Dequeued(UUID.fromString(raw), tierForKey(key));
        } catch (IllegalArgumentException e) {
            log.warn("Discarding non-UUID entry '{}' from {}", raw, key);
            return null;
        }
    }

    /**
     * Park a job until {@code dueAt}, remembering its tier.
     *
     * <p>The member is {@code <TIER>:<uuid>} rather than a bare UUID. The sorted set is one key
     * with no tier of its own, so the promotion has to learn the tier from somewhere; encoding it
     * in the member keeps range + ZREM + LPUSH inside a single atomic script, where a database
     * lookup per promoted job would not. See NOTES.md for what that denormalisation costs.
     *
     * <p>ZADD still overwrites the score of a member already present, so re-scheduling the same
     * job is idempotent — and stays that way precisely because priority is immutable, which is
     * what stops the encoded tier drifting from the value in Postgres.
     */
    public void scheduleAt(UUID jobId, Priority priority, Instant dueAt) {
        redis.opsForZSet().add(delayedKey, member(jobId, priority), dueAt.toEpochMilli());
        log.debug("Scheduled job {} ({}) on {} for {}", jobId, priority, delayedKey, dueAt);
    }

    /**
     * Move up to {@code limit} jobs whose due time has passed onto their own tier's pending list.
     *
     * @return how many were moved
     */
    public int promoteDueJobs(Instant now, int limit) {
        List<String> keys = new ArrayList<>();
        keys.add(delayedKey);
        Priority.STRICT_ORDER.forEach(priority -> keys.add(keyFor(priority)));

        List<String> args = new ArrayList<>();
        args.add(Long.toString(now.toEpochMilli()));
        args.add(Integer.toString(limit));
        Priority.STRICT_ORDER.forEach(priority -> args.add(priority.name()));
        args.add(Priority.DEFAULT.name());

        Long moved = redis.execute(promoteScript, keys, args.toArray());
        return moved == null ? 0 : moved.intValue();
    }

    /** Per-tier pending depth, every tier present even at zero. */
    public Map<Priority, Long> depthByPriority() {
        Map<Priority, Long> depths = new LinkedHashMap<>();
        for (Priority priority : Priority.STRICT_ORDER) {
            Long size = redis.opsForList().size(keyFor(priority));
            depths.put(priority, size == null ? 0L : size);
        }
        return depths;
    }

    /** Total pending depth across all tiers, so the flat v0.3 metric keeps meaning the same thing. */
    public long depth() {
        return depthByPriority().values().stream().mapToLong(Long::longValue).sum();
    }

    public long delayedDepth() {
        Long size = redis.opsForZSet().zCard(delayedKey);
        return size == null ? 0L : size;
    }

    public String keyFor(Priority priority) {
        return tierKeys.get(Priority.orDefault(priority));
    }

    private String member(UUID jobId, Priority priority) {
        return Priority.orDefault(priority).name() + TIER_SEPARATOR + jobId;
    }

    private Priority tierForKey(String key) {
        return tierKeys.entrySet().stream()
                .filter(entry -> entry.getValue().equals(key))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(Priority.DEFAULT);
    }
}
