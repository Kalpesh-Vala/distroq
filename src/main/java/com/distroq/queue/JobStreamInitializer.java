package com.distroq.queue;

import com.distroq.config.DistroqProperties;
import com.distroq.model.Priority;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisListCommands.Direction;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Makes Redis ready for the worker: consumer groups first, then the one-time list migration.
 *
 * <p>Runs in {@code @PostConstruct} rather than on {@code ApplicationReadyEvent} because
 * {@link JobStreamConsumer} takes this bean as a constructor argument. That argument is unused at
 * runtime and exists purely to order construction: the first {@code XREADGROUP} must not be able
 * to run before the group it names exists.
 */
@Component
public class JobStreamInitializer {

    private static final Logger log = LoggerFactory.getLogger(JobStreamInitializer.class);

    /** Redis' reply when XGROUP CREATE finds the group already there. Not an error here. */
    private static final String ALREADY_EXISTS = "BUSYGROUP";

    /** Bounds the migration drain loops, so a key that will not shrink cannot hang startup. */
    private static final int MAX_MIGRATED_PER_KEY = 100_000;

    private final StringRedisTemplate redis;
    private final JobQueue jobQueue;
    private final StreamKeys streamKeys;
    private final String legacyQueueKey;
    private final String migrationKeyPrefix;
    private final String groupName;
    private final String groupStartId;

    public JobStreamInitializer(StringRedisTemplate redis,
                                JobQueue jobQueue,
                                StreamKeys streamKeys,
                                DistroqProperties properties) {
        this.redis = redis;
        this.jobQueue = jobQueue;
        this.streamKeys = streamKeys;
        this.legacyQueueKey = properties.queueKey();
        this.migrationKeyPrefix = "distroq:jobs:migration:pending";
        this.groupName = properties.streams().groupName();
        this.groupStartId = properties.streams().groupStartId();
    }

    @PostConstruct
    void initialise() {
        ensureGroups();
        migrateLegacyLists();
    }

    /**
     * {@code XGROUP CREATE <stream> <group> <start> MKSTREAM} on each tier.
     *
     * <p>{@code MKSTREAM} is what makes this work on a clean Redis: without it the command fails
     * because the stream key does not exist yet, and nothing would create it until the first
     * submission. The whole operation is idempotent — a group that is already there answers
     * BUSYGROUP, which is a success for our purposes, not a startup failure.
     */
    private void ensureGroups() {
        for (Priority priority : Priority.STRICT_ORDER) {
            String key = streamKeys.keyFor(priority);
            try {
                redis.execute((RedisCallback<String>) connection -> connection.streamCommands()
                        .xGroupCreate(key.getBytes(StandardCharsets.UTF_8), groupName,
                                ReadOffset.from(groupStartId), true));
                log.info("Created consumer group {} on {}", groupName, key);
            } catch (Exception e) {
                if (alreadyExists(e)) {
                    log.info("Consumer group {} already exists on {}", groupName, key);
                } else {
                    throw e;
                }
            }
        }
    }

    private static boolean alreadyExists(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains(ALREADY_EXISTS)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Move anything left on a v0.4 pending list onto the matching stream.
     *
     * <p>Nothing reads those lists any more, so an ID left behind is a job sitting in PostgreSQL
     * as QUEUED that no worker will ever pick up. The alternative was to document "drain the queue
     * before upgrading", which turns silently stranded work into a documentation problem rather
     * than a code problem.
     *
     * <p>Not transactional, and not claimed to be. A pop followed by an {@code XADD} is two
     * commands, so the process can die between them. {@code LMOVE} onto a per-tier parking list
     * narrows the window to the safe side: the ID is on exactly one list at all times, and a crash
     * after {@code XADD} but before {@code LREM} produces a duplicate stream entry on the next
     * startup rather than a lost job. A duplicate is something the worker's redelivery handling
     * already copes with; a loss is not.
     */
    private void migrateLegacyLists() {
        Map<String, Priority> sources = new LinkedHashMap<>();
        for (Priority priority : Priority.STRICT_ORDER) {
            sources.put(legacyQueueKey + ':' + priority.keySuffix(), priority);
        }
        // the unqualified v0.3 key carried no tier at all, so it can only mean the default one
        sources.put(legacyQueueKey, Priority.DEFAULT);

        long total = 0;
        for (Map.Entry<String, Priority> source : sources.entrySet()) {
            total += migrate(source.getKey(), source.getValue());
        }
        if (total == 0) {
            log.debug("No v0.4 list entries to migrate");
        }
        verifyDrained(sources.keySet());
    }

    private long migrate(String sourceKey, Priority tier) {
        String parkingKey = migrationKeyPrefix + ':' + tier.keySuffix();
        long migrated = 0;
        try {
            // anything already parked is a previous run that died mid-migration
            migrated += drainParking(parkingKey, tier);

            while (migrated < MAX_MIGRATED_PER_KEY) {
                String id = redis.opsForList().move(sourceKey, Direction.RIGHT, parkingKey, Direction.LEFT);
                if (id == null) {
                    break;
                }
                publish(id, tier);
                redis.opsForList().remove(parkingKey, 1, id);
                migrated++;
            }

            if (migrated > 0) {
                log.warn("Migrated {} job ID(s) from the v0.4 list {} onto the {} stream",
                        migrated, sourceKey, tier);
            }
        } catch (Exception e) {
            log.error("Migration of {} failed after {} entr(ies); the remainder stays on {} or {}",
                    sourceKey, migrated, sourceKey, parkingKey, e);
        }
        return migrated;
    }

    private long drainParking(String parkingKey, Priority tier) {
        long drained = 0;
        while (drained < MAX_MIGRATED_PER_KEY) {
            // LMOVE pushed to the head, so the tail is the oldest parked entry
            String id = redis.opsForList().index(parkingKey, -1);
            if (id == null) {
                break;
            }
            publish(id, tier);
            Long removed = redis.opsForList().remove(parkingKey, 1, id);
            if (removed == null || removed == 0) {
                log.error("Could not remove {} from {}; abandoning the parked-entry drain to "
                        + "avoid spinning", id, parkingKey);
                break;
            }
            drained++;
        }
        if (drained > 0) {
            log.warn("Recovered {} job ID(s) parked on {} by an interrupted migration",
                    drained, parkingKey);
        }
        return drained;
    }

    private void publish(String rawId, Priority tier) {
        UUID jobId;
        try {
            jobId = UUID.fromString(rawId.trim());
        } catch (IllegalArgumentException e) {
            log.warn("Discarding non-UUID legacy list entry '{}'", rawId);
            return;
        }
        String entryId = jobQueue.enqueue(jobId, tier, EnqueueSource.LEGACY_MIGRATION);
        log.info("Migrated job {} to the {} stream as entry {}", jobId, tier, entryId);
    }

    private void verifyDrained(Iterable<String> keys) {
        for (String key : keys) {
            Long size = redis.opsForList().size(key);
            if (size != null && size > 0) {
                log.error("Legacy list {} still holds {} entr(ies) after migration", key, size);
            }
        }
    }
}
