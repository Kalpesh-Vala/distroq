package com.distroq.queue;

import io.lettuce.core.RedisFuture;
import io.lettuce.core.XAutoClaimArgs;
import io.lettuce.core.api.async.RedisStreamAsyncCommands;
import io.lettuce.core.models.stream.ClaimedMessages;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The two stream commands Spring Data Redis 3.5.13 does not expose, bound directly to Lettuce.
 *
 * <p>Verified against the resolved dependencies rather than assumed:
 * {@code StreamOperations} in spring-data-redis 3.5.13 has {@code claim(..)} but no
 * {@code autoClaim}, and {@code StreamInfo.XInfoGroup} exposes {@code pendingCount()} and
 * {@code lastDeliveredId()} but not the Redis 7 {@code lag} field. Both are present in
 * lettuce-core 6.6.0.RELEASE as {@code xautoclaim} and {@code xinfoGroups}.
 *
 * <p>Everything version-specific lives here so the rest of the queue package keeps talking to
 * Spring Data. {@code getNativeConnection()} on a Lettuce connection returns the shared async
 * command set, so these calls do not open a connection of their own.
 */
@Component
public class LettuceStreamCommands {

    /** Neither command blocks server-side; a wait this long means the connection is gone. */
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(5);

    private static final String XINFO_FIELD_NAME = "name";
    private static final String XINFO_FIELD_LAG = "lag";

    private final StringRedisTemplate redis;

    public LettuceStreamCommands(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * {@code XAUTOCLAIM key group consumer min-idle-time start COUNT count}.
     *
     * @return the next cursor and the entries transferred to {@code consumer}; a cursor of
     *         {@code 0-0} means the scan wrapped and there is nothing further in this pass
     */
    public AutoClaimResult autoClaim(String streamKey, String group, String consumer,
                                     Duration minIdle, long count, String startId) {
        XAutoClaimArgs<byte[]> args = new XAutoClaimArgs<byte[]>()
                .consumer(io.lettuce.core.Consumer.from(bytes(group), bytes(consumer)))
                .minIdleTime(minIdle)
                .startId(startId)
                .count(count);

        ClaimedMessages<byte[], byte[]> claimed = call(commands ->
                commands.xautoclaim(bytes(streamKey), args));

        List<ClaimedEntry> entries = new ArrayList<>();
        claimed.getMessages().forEach(message ->
                entries.add(new ClaimedEntry(message.getId(), decode(message.getBody()))));
        return new AutoClaimResult(claimed.getId(), entries);
    }

    /**
     * The group's {@code lag}: entries added to the stream that the group has never been offered.
     *
     * <p>Redis 7 reports this as null once entries have been deleted or trimmed away, because the
     * count can no longer be derived. Nothing here trims, so in practice it is exact — but the
     * null is propagated rather than papered over with a zero.
     */
    public Long groupLag(String streamKey, String group) {
        List<Object> reply = call(commands -> commands.xinfoGroups(bytes(streamKey)));
        for (Object entry : reply) {
            if (!(entry instanceof List<?> fields)) {
                continue;
            }
            Map<String, Object> asMap = pairs(fields);
            if (group.equals(asString(asMap.get(XINFO_FIELD_NAME)))
                    && asMap.get(XINFO_FIELD_LAG) instanceof Long lag) {
                return lag;
            }
        }
        return null;
    }

    private <T> T call(java.util.function.Function<RedisStreamAsyncCommands<byte[], byte[]>,
            RedisFuture<T>> command) {
        return redis.execute((RedisCallback<T>) connection -> {
            Object nativeConnection = connection.getNativeConnection();
            if (!(nativeConnection instanceof RedisStreamAsyncCommands)) {
                throw new InvalidDataAccessApiUsageException(
                        "Redis Streams recovery needs a Lettuce connection, got "
                                + nativeConnection.getClass().getName());
            }
            @SuppressWarnings("unchecked")
            RedisStreamAsyncCommands<byte[], byte[]> commands =
                    (RedisStreamAsyncCommands<byte[], byte[]>) nativeConnection;
            try {
                return command.apply(commands).get(CALL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted waiting for a Redis stream command", e);
            } catch (ExecutionException | TimeoutException e) {
                throw new IllegalStateException("Redis stream command failed", e);
            }
        });
    }

    /** XINFO replies are flat field/value arrays, and under the byte[] codec so are the names. */
    private static Map<String, Object> pairs(List<?> flat) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < flat.size(); i += 2) {
            map.put(asString(flat.get(i)), flat.get(i + 1));
        }
        return map;
    }

    private static String asString(Object value) {
        return value instanceof byte[] raw ? new String(raw, StandardCharsets.UTF_8) : String.valueOf(value);
    }

    private static Map<String, String> decode(Map<byte[], byte[]> body) {
        Map<String, String> decoded = new LinkedHashMap<>();
        body.forEach((key, value) -> decoded.put(asString(key), asString(value)));
        return decoded;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    public record AutoClaimResult(String nextCursor, List<ClaimedEntry> entries) {
    }

    public record ClaimedEntry(String entryId, Map<String, String> fields) {
    }
}
