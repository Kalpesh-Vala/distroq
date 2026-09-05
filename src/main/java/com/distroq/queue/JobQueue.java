package com.distroq.queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * Redis holds job IDs only; PostgreSQL remains the source of truth.
 */
@Component
public class JobQueue {

    private static final Logger log = LoggerFactory.getLogger(JobQueue.class);

    private final StringRedisTemplate redis;
    private final String queueKey;

    public JobQueue(StringRedisTemplate redis, @Value("${distroq.queue-key}") String queueKey) {
        this.redis = redis;
        this.queueKey = queueKey;
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

    public long depth() {
        Long size = redis.opsForList().size(queueKey);
        return size == null ? 0L : size;
    }
}
