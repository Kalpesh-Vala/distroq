package com.distroq.queue;

import com.distroq.config.DistroqProperties;
import com.distroq.model.Priority;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Read side of the queue. Every delivery goes through {@code XREADGROUP}; plain {@code XREAD} is
 * never used, because it does not create a pending entry and so cannot be acknowledged, inspected
 * or reclaimed.
 *
 * <p><b>Consumer identity.</b> The name is generated once per process and is also the worker's ID
 * in {@code job_attempts}. Two application instances sharing a name would each see the other's
 * in-flight entries as their own pending work, so the random suffix is not cosmetic.
 *
 * <p><b>Priority.</b> Redis has no equivalent of v0.4's multi-key {@code BRPOP} — there is no
 * single call that says "read from these streams, preferring this one". Priority is therefore a
 * property of this read strategy rather than of Redis, and it is implemented as the brief's
 * preferred approach: non-blocking reads in the order {@code PriorityStrategy} asks for, highest
 * first, falling back to one bounded blocking read across all three only when every tier is empty.
 *
 * <p>The cost is up to four round trips on an idle queue instead of one. What it buys is that a
 * tier is only skipped after Redis has actually said it is empty, which is as close to v0.4's
 * guarantee as three independent streams allow. The one place it is weaker: during the final
 * blocking read, an entry arriving on LOW wakes the call even if one arrives on HIGH a moment
 * later, so the very first entry after an idle period is served in arrival order, not tier order.
 *
 * <p>Reads never discard. An entry returned by {@code XREADGROUP} is already in this consumer's
 * PEL, so dropping it would strand it until the idle timeout; the blocking read can return one
 * entry per stream, and all of them are handed back for processing in tier order.
 */
@Component
public class JobStreamConsumer {

    private static final Logger log = LoggerFactory.getLogger(JobStreamConsumer.class);

    private final StringRedisTemplate redis;
    private final StreamKeys streamKeys;
    private final LettuceStreamCommands lettuce;
    private final String groupName;
    private final String consumerName;
    private final long readCount;
    private final Duration blockTimeout;

    public JobStreamConsumer(StringRedisTemplate redis,
                             StreamKeys streamKeys,
                             LettuceStreamCommands lettuce,
                             DistroqProperties properties,
                             // unused: forces the consumer groups to exist before the first read
                             JobStreamInitializer initializer) {
        this.redis = redis;
        this.streamKeys = streamKeys;
        this.lettuce = lettuce;
        this.groupName = properties.streams().groupName();
        this.readCount = Math.max(1, properties.streams().readCount());
        this.blockTimeout = Duration.ofMillis(Math.max(1, properties.streams().blockTimeoutMs()));
        this.consumerName = properties.streams().consumerNamePrefix()
                + '-' + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    public String consumerName() {
        return consumerName;
    }

    public String groupName() {
        return groupName;
    }

    /**
     * New entries only ({@code >}), attempted in the order given.
     *
     * @return every entry delivered by this poll, highest tier first; empty when the blocking read
     *         timed out with all tiers idle
     */
    public List<StreamDelivery> poll(List<Priority> order) {
        List<Priority> tiers = order == null || order.isEmpty() ? Priority.STRICT_ORDER : order;

        for (Priority tier : tiers) {
            List<StreamDelivery> served = read(StreamReadOptions.empty().count(readCount), List.of(tier), tiers);
            if (!served.isEmpty()) {
                return served;
            }
        }
        return read(StreamReadOptions.empty().count(readCount).block(blockTimeout), tiers, tiers);
    }

    /**
     * {@code XACK}. Called only once the database already reflects the outcome, so a crash before
     * this point costs a redelivery rather than a lost job.
     */
    public void acknowledge(String streamKey, String entryId) {
        Long acked = streamOps().acknowledge(streamKey, groupName, entryId);
        log.debug("XACK {} {} on {} acknowledged {} entr(ies)", groupName, entryId, streamKey, acked);
    }

    /**
     * {@code XAUTOCLAIM} one batch of entries idle longer than {@code minIdle} on one tier,
     * transferring ownership to this consumer.
     *
     * @param startId the cursor to resume from; {@code 0-0} starts a fresh scan
     */
    public ClaimedBatch claimStale(Priority tier, Duration minIdle, int count, String startId) {
        String streamKey = streamKeys.keyFor(tier);
        LettuceStreamCommands.AutoClaimResult result =
                lettuce.autoClaim(streamKey, groupName, consumerName, minIdle, count, startId);

        List<StreamDelivery> deliveries = new ArrayList<>();
        for (LettuceStreamCommands.ClaimedEntry claimed : result.entries()) {
            toDelivery(streamKey, claimed.entryId(), claimed.fields()).ifPresent(deliveries::add);
        }
        return new ClaimedBatch(result.nextCursor(), result.entries().size(), deliveries);
    }

    private List<StreamDelivery> read(StreamReadOptions options,
                                      List<Priority> from,
                                      List<Priority> tierOrder) {
        @SuppressWarnings("unchecked")
        StreamOffset<String>[] offsets = from.stream()
                .map(tier -> StreamOffset.create(streamKeys.keyFor(tier), ReadOffset.lastConsumed()))
                .toArray(StreamOffset[]::new);

        List<MapRecord<String, String, String>> records =
                streamOps().read(Consumer.from(groupName, consumerName), options, offsets);
        if (records == null || records.isEmpty()) {
            return List.of();
        }

        List<StreamDelivery> deliveries = new ArrayList<>(records.size());
        for (MapRecord<String, String, String> record : records) {
            toDelivery(record.getStream(), record.getId().getValue(), record.getValue())
                    .ifPresent(deliveries::add);
        }
        // a multi-stream read can answer from more than one tier at once; serve the highest first
        deliveries.sort(Comparator.comparingInt(delivery -> tierOrder.indexOf(delivery.streamPriority())));
        return deliveries;
    }

    /**
     * Empty only when the entry has no usable job ID. Such an entry is dropped here and left
     * pending deliberately: acknowledging unparseable data would erase the evidence, and it will
     * keep reappearing in {@code XPENDING} until someone looks at it.
     */
    private Optional<StreamDelivery> toDelivery(String streamKey, String entryId, Map<String, String> fields) {
        Optional<JobStreamEntry> entry = JobStreamEntry.parse(fields);
        if (entry.isEmpty()) {
            log.error("Entry {} on {} has no readable jobId ({}); leaving it pending for inspection",
                    entryId, streamKey, fields);
            return Optional.empty();
        }
        Priority routedAt = streamKeys.priorityFor(streamKey).orElse(entry.get().priority());
        return Optional.of(new StreamDelivery(streamKey, entryId, entry.get().jobId(), routedAt, fields));
    }

    private StreamOperations<String, String, String> streamOps() {
        return redis.opsForStream();
    }

    /**
     * One {@code XAUTOCLAIM} pass.
     *
     * @param nextCursor {@code 0-0} when the scan reached the end of the pending list
     * @param claimed    entries transferred, including any that could not be parsed
     * @param deliveries the subset that can actually be executed
     */
    public record ClaimedBatch(String nextCursor, int claimed, List<StreamDelivery> deliveries) {
    }
}
