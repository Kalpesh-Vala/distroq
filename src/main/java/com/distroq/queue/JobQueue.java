package com.distroq.queue;

import com.distroq.config.DistroqProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Redis holds job IDs only; PostgreSQL remains the source of truth.
 *
 * <p>Two structures: a list of IDs that are ready to run now, and a sorted set of IDs scored
 * by the epoch-millis instant at which they become eligible.
 */
@Component
public class JobQueue {

    private static final Logger log = LoggerFactory.getLogger(JobQueue.class);

    /**
     * Range + remove + push in one server-side round trip, so no ID can be promoted twice.
     */
    private static final String PROMOTE_LUA = """
            local due = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1], 'LIMIT', 0, ARGV[2])
            local moved = 0
            for i = 1, #due do
              if redis.call('ZREM', KEYS[1], due[i]) == 1 then
                redis.call('LPUSH', KEYS[2], due[i])
                moved = moved + 1
              end
            end
            return moved
            """;

    private final StringRedisTemplate redis;
    private final String queueKey;
    private final String delayedKey;
    private final RedisScript<Long> promoteScript;

    public JobQueue(StringRedisTemplate redis, DistroqProperties properties) {
        this.redis = redis;
        this.queueKey = properties.queueKey();
        this.delayedKey = properties.delayedKey();
        this.promoteScript = new DefaultRedisScript<>(PROMOTE_LUA, Long.class);
    }

    public void enqueue(UUID jobId) {
        redis.opsForList().leftPush(queueKey, jobId.toString());
        log.debug("Enqueued job {} onto {}", jobId, queueKey);
    }

    /**
     * Blocking BRPOP. Returns null when the timeout elapses with nothing to pop.
     */
    public UUID dequeue(Duration timeout) {
        String raw = redis.opsForList().rightPop(queueKey, timeout);
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            log.warn("Discarding non-UUID entry '{}' from {}", raw, queueKey);
            return null;
        }
    }

    /**
     * Park a job until {@code dueAt}. ZADD overwrites the score of an ID already present, so
     * re-scheduling the same job is idempotent rather than duplicating it.
     */
    public void scheduleAt(UUID jobId, Instant dueAt) {
        redis.opsForZSet().add(delayedKey, jobId.toString(), dueAt.toEpochMilli());
        log.debug("Scheduled job {} on {} for {}", jobId, delayedKey, dueAt);
    }

    /**
     * Move up to {@code limit} jobs whose due time has passed onto the pending list.
     *
     * @return how many were moved
     */
    public int promoteDueJobs(Instant now, int limit) {
        Long moved = redis.execute(
                promoteScript,
                List.of(delayedKey, queueKey),
                Long.toString(now.toEpochMilli()),
                Integer.toString(limit));
        return moved == null ? 0 : moved.intValue();
    }

    public long depth() {
        Long size = redis.opsForList().size(queueKey);
        return size == null ? 0L : size;
    }

    public long delayedDepth() {
        Long size = redis.opsForZSet().zCard(delayedKey);
        return size == null ? 0L : size;
    }
}
