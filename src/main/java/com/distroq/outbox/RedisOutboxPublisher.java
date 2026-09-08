package com.distroq.outbox;

import com.distroq.config.DistroqProperties;
import com.distroq.model.OutboxEvent;
import com.distroq.queue.SortedSetMember;
import com.distroq.queue.StreamKeys;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RedisOutboxPublisher {

    private static final String STREAM_LUA = """
            local previous = redis.call('GET', KEYS[1])
            if previous then return previous end
            local result = redis.call('XADD', KEYS[2], '*',
              'jobId', ARGV[2], 'priority', ARGV[3], 'enqueuedAt', ARGV[4],
              'source', ARGV[5], 'outboxEventId', ARGV[6])
            redis.call('SET', KEYS[1], result, 'PX', ARGV[1])
            return result
            """;

    private static final String SORTED_SET_LUA = """
            local previous = redis.call('GET', KEYS[1])
            if previous then return previous end
            redis.call('ZADD', KEYS[2], ARGV[2], ARGV[3])
            redis.call('SET', KEYS[1], ARGV[3], 'PX', ARGV[1])
            return ARGV[3]
            """;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final StreamKeys streamKeys;
    private final DistroqProperties properties;
    private final RedisScript<String> streamScript = new DefaultRedisScript<>(STREAM_LUA, String.class);
    private final RedisScript<String> sortedSetScript =
            new DefaultRedisScript<>(SORTED_SET_LUA, String.class);

    public RedisOutboxPublisher(StringRedisTemplate redis, ObjectMapper objectMapper,
                                StreamKeys streamKeys, DistroqProperties properties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.streamKeys = streamKeys;
        this.properties = properties;
    }

    public String publish(OutboxEvent event) {
        OutboxPayload payload = payload(event);
        if (!event.getId().equals(payload.eventId()) || !event.getAggregateId().equals(payload.jobId())) {
            throw new IllegalArgumentException("Outbox event identity does not match its payload");
        }
        return switch (event.getEventType()) {
            case ENQUEUE_SUBMIT, ENQUEUE_REPLAY -> publishStream(payload);
            case SCHEDULE_RETRY -> publishSortedSet(properties.delayedKey(), payload);
            case SCHEDULE_USER_JOB -> publishSortedSet(properties.scheduledKey(), payload);
        };
    }

    private String publishStream(OutboxPayload payload) {
        return redis.execute(streamScript,
                List.of(dedupeKey(payload), streamKeys.keyFor(payload.priority())),
                Long.toString(properties.outbox().dedupeRetentionMs()),
                payload.jobId().toString(), payload.priority().name(),
                Long.toString(System.currentTimeMillis()), payload.source().name(),
                payload.eventId().toString());
    }

    private String publishSortedSet(String key, OutboxPayload payload) {
        if (payload.dueAt() == null) {
            throw new IllegalArgumentException("Scheduled outbox event has no dueAt");
        }
        String member = SortedSetMember.encode(payload.jobId(), payload.priority(), payload.eventId());
        return redis.execute(sortedSetScript, List.of(dedupeKey(payload), key),
                Long.toString(properties.outbox().dedupeRetentionMs()),
                Long.toString(payload.dueAt().toEpochMilli()), member);
    }

    private OutboxPayload payload(OutboxEvent event) {
        try {
            return objectMapper.readValue(event.getPayload(), OutboxPayload.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Malformed outbox payload for " + event.getId(), e);
        }
    }

    private static String dedupeKey(OutboxPayload payload) {
        return "distroq:outbox:published:" + payload.eventId();
    }
}