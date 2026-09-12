package com.distroq.dashboard;

import com.distroq.config.DistroqProperties;
import com.distroq.model.Priority;
import com.distroq.queue.LettuceStreamCommands;
import com.distroq.queue.SortedSetMember;
import com.distroq.queue.StreamKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything the dashboard reads out of Redis, and nothing else.
 *
 * <p>Five commands, all of them reads: {@code XLEN}, {@code XINFO GROUPS}, {@code XINFO
 * CONSUMERS}, {@code XPENDING} and {@code ZCARD}/{@code ZRANGE}. There is no {@code XACK}, no
 * {@code XAUTOCLAIM}, no {@code XADD}, no {@code ZADD} and no {@code ZREM} here, which is the
 * whole of the "the dashboard does not mutate Redis" claim — it is a property of this file, and a
 * reviewer can check it by reading the imports.
 *
 * <p>Deliberately separate from {@link com.distroq.queue.JobQueue}. That class is the write side
 * of the queue and the source of {@code /api/metrics}; adding the dashboard's richer inspection to
 * it would put read-only reporting inside the component workers depend on. The overlap in the two
 * cheap counts is intentional duplication of a Redis command, not shared state.
 *
 * <p>The four counts stay apart on purpose, and the field names say which is which.
 * {@code streamLength} is {@code XLEN} — every entry the stream has ever held, acknowledged or
 * not, and never a backlog. {@code readyDepth} is the consumer group's {@code lag} — entries the
 * group has not yet been offered, and the only one of them that means "waiting to execute".
 * {@code pendingEntries} is delivered-and-unacknowledged. Scheduled and delayed live in sorted
 * sets and are not stream state at all.
 */
@Component
public class QueueInspector {

    private static final Logger log = LoggerFactory.getLogger(QueueInspector.class);

    /** A hard ceiling on {@code XPENDING} detail, so an abandoned backlog cannot become a page. */
    static final int MAX_PENDING_DETAIL = 50;

    private final StringRedisTemplate redis;
    private final StreamKeys streamKeys;
    private final LettuceStreamCommands lettuce;
    private final String groupName;
    private final String scheduledKey;
    private final String delayedKey;

    public QueueInspector(StringRedisTemplate redis,
                          StreamKeys streamKeys,
                          LettuceStreamCommands lettuce,
                          DistroqProperties properties) {
        this.redis = redis;
        this.streamKeys = streamKeys;
        this.lettuce = lettuce;
        this.groupName = properties.streams().groupName();
        this.scheduledKey = properties.scheduledKey();
        this.delayedKey = properties.delayedKey();
    }

    /**
     * One row per priority tier, in strict scheduling order.
     *
     * @param readyDepth null when Redis cannot derive the group's lag, which is distinct from a
     *                   lag of zero and is rendered as "no data" rather than as 0
     */
    public record StreamSnapshot(String priority,
                                 String streamKey,
                                 long streamLength,
                                 Long readyDepth,
                                 long pendingEntries,
                                 Long oldestPendingEntryAgeMs,
                                 List<ConsumerSnapshot> consumers) {
    }

    /**
     * A consumer registered in the group, whether or not it currently holds anything.
     *
     * <p>{@code idleTimeMs} is time since this consumer last interacted with the group, which is
     * the closest thing Redis has to a heartbeat. It says nothing about whether the consumer owns
     * a database execution lease; that question is answered by the jobs table.
     */
    public record ConsumerSnapshot(String consumerName,
                                   String streamKey,
                                   String priority,
                                   long pendingCount,
                                   long idleTimeMs) {
    }

    /** One entry a consumer was handed and has not acknowledged. No payload fields are read. */
    public record PendingEntrySnapshot(String entryId,
                                       String consumerName,
                                       String priority,
                                       long idleMs,
                                       long deliveryCount,
                                       Long ageMs) {
    }

    public record SortedSetSnapshot(long total, Map<String, Long> byPriority) {
    }

    public List<StreamSnapshot> streams() {
        List<StreamSnapshot> snapshots = new ArrayList<>(Priority.STRICT_ORDER.size());
        for (Priority priority : Priority.STRICT_ORDER) {
            String key = streamKeys.keyFor(priority);
            Long length = streamOps().size(key);
            PendingMessagesSummary pending = streamOps().pending(key, groupName);
            snapshots.add(new StreamSnapshot(
                    priority.name(),
                    key,
                    length == null ? 0L : length,
                    lettuce.groupLag(key, groupName),
                    pending == null ? 0L : pending.getTotalPendingMessages(),
                    oldestPendingAge(pending),
                    consumersOn(key, priority)));
        }
        return snapshots;
    }

    /** Bounded {@code XPENDING} detail for the workers page. */
    public List<PendingEntrySnapshot> pendingEntries(int limit) {
        int bounded = Math.clamp(limit, 1, MAX_PENDING_DETAIL);
        List<PendingEntrySnapshot> entries = new ArrayList<>();
        for (Priority priority : Priority.STRICT_ORDER) {
            if (entries.size() >= bounded) {
                break;
            }
            String key = streamKeys.keyFor(priority);
            PendingMessages messages = streamOps().pending(key, groupName,
                    Range.unbounded(), bounded - entries.size());
            if (messages == null) {
                continue;
            }
            for (PendingMessage message : messages) {
                entries.add(new PendingEntrySnapshot(
                        message.getIdAsString(),
                        message.getConsumerName(),
                        priority.name(),
                        message.getElapsedTimeSinceLastDelivery().toMillis(),
                        message.getTotalDeliveryCount(),
                        ageOfEntry(message.getIdAsString())));
            }
        }
        return entries;
    }

    public SortedSetSnapshot scheduled() {
        return sortedSet(scheduledKey);
    }

    public SortedSetSnapshot delayed() {
        return sortedSet(delayedKey);
    }

    private List<ConsumerSnapshot> consumersOn(String streamKey, Priority priority) {
        List<ConsumerSnapshot> consumers = new ArrayList<>();
        StreamInfo.XInfoConsumers info = streamOps().consumers(streamKey, groupName);
        if (info == null) {
            return consumers;
        }
        for (StreamInfo.XInfoConsumer consumer : info) {
            consumers.add(new ConsumerSnapshot(consumer.consumerName(), streamKey,
                    priority.name(), consumer.pendingCount(), consumer.idleTimeMs()));
        }
        return consumers;
    }

    /**
     * {@code ZRANGE 0 -1} and count, mirroring {@code ScheduledJobQueue#scheduledDepthByPriority}.
     * O(N) in outstanding waiting work rather than in history, and bounded by the same thing the
     * metrics endpoint is already bounded by.
     */
    private SortedSetSnapshot sortedSet(String key) {
        Map<String, Long> byPriority = new LinkedHashMap<>();
        for (Priority priority : Priority.STRICT_ORDER) {
            byPriority.put(priority.name(), 0L);
        }
        Long total = redis.opsForZSet().zCard(key);
        Set<String> members = redis.opsForZSet().range(key, 0, -1);
        if (members != null) {
            for (String raw : members) {
                SortedSetMember.parse(raw).ifPresentOrElse(
                        parsed -> byPriority.merge(parsed.priority().name(), 1L, Long::sum),
                        () -> log.debug("Unreadable member on {}; excluded from the breakdown", key));
            }
        }
        return new SortedSetSnapshot(total == null ? 0L : total, byPriority);
    }

    private static Long oldestPendingAge(PendingMessagesSummary summary) {
        if (summary == null || summary.getTotalPendingMessages() == 0) {
            return null;
        }
        return ageOfEntry(summary.minMessageId());
    }

    /**
     * A stream entry ID is {@code <millisecondsSinceEpoch>-<sequence>}, so the age of the oldest
     * pending entry is readable without fetching the entry itself — and therefore without reading
     * any payload field.
     */
    static Long ageOfEntry(String entryId) {
        if (entryId == null) {
            return null;
        }
        int dash = entryId.indexOf('-');
        String millis = dash < 0 ? entryId : entryId.substring(0, dash);
        try {
            return Math.max(0L, Instant.now().toEpochMilli() - Long.parseLong(millis));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private StreamOperations<String, String, String> streamOps() {
        return redis.opsForStream();
    }
}
