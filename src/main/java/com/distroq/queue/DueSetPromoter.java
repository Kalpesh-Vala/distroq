package com.distroq.queue;

import com.distroq.model.Priority;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Moves due members off a sorted set and onto their tier's stream, atomically.
 *
 * <p>Two sorted sets need exactly this operation and differ only in which key they read and what
 * they call the result: the v0.4 delayed set feeds retries, and the v0.6 scheduled set feeds
 * user-requested execution times. The Lua lives here once rather than twice, because the one thing
 * worse than a copy of thirty lines of Lua is a copy that has drifted.
 *
 * <p>Range + {@code ZREM} + {@code XADD} in one server-side round trip, so no ID can be promoted
 * twice: another process running the same script cannot interleave with this one, and the
 * {@code ZREM} return value is what decides whether this caller owns the member.
 *
 * <p>{@code XADD *} inside a script is safe on Redis 5+: scripts replicate by their effects, so a
 * replica receives the ID the primary generated rather than generating its own.
 *
 * <p>Atomic inside Redis, and only inside Redis. Nothing here touches PostgreSQL, so a job whose
 * row still says SCHEDULED while its entry is already on a stream is a normal intermediate state
 * that the worker resolves, not a failure. See NOTES.md.
 */
@Component
public class DueSetPromoter {

    /**
     * KEYS[1] is the sorted set and KEYS[2..] the tier streams in {@link Priority} declaration
     * order. ARGV[3..] are the matching tier names, then the default tier name, then the source
     * label — so the script never builds a key name or an enum name itself.
     *
     * <p>A member with no separator, or with a tier this application does not recognise, is
     * treated as a bare UUID and goes to the default tier. That is deliberately more forgiving
     * than {@link SortedSetMember#parse}: a member written by another version should still move
     * somewhere a worker will look at it, and the worker will correct the tier against PostgreSQL
     * anyway. Nothing here can crash the sweep on unexpected input.
     */
    private static final String PROMOTE_LUA = """
            local due = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1], 'LIMIT', 0, ARGV[2])
            local tiers = #KEYS - 1
            local dest = {}
            for i = 1, tiers do
              dest[ARGV[i + 2]] = KEYS[i + 1]
            end
            local fallbackTier = ARGV[tiers + 3]
            local source = ARGV[tiers + 4]
            local moved = 0
            for i = 1, #due do
              local member = due[i]
              if redis.call('ZREM', KEYS[1], member) == 1 then
                local sep = string.find(member, ':', 1, true)
                local id = member
                local tier = fallbackTier
                if sep then
                  local parsed = string.sub(member, 1, sep - 1)
                  if dest[parsed] then
                    tier = parsed
                    id = string.sub(member, sep + 1)
                  end
                end
                local eventSep = string.find(id, ':', 1, true)
                if eventSep then
                  local eventId = string.sub(id, eventSep + 1)
                  id = string.sub(id, 1, eventSep - 1)
                  redis.call('XADD', dest[tier], '*',
                    'jobId', id, 'priority', tier, 'enqueuedAt', ARGV[1],
                    'source', source, 'outboxEventId', eventId)
                else
                  redis.call('XADD', dest[tier], '*',
                    'jobId', id, 'priority', tier, 'enqueuedAt', ARGV[1], 'source', source)
                end
                moved = moved + 1
              end
            end
            return moved
            """;

    private final StringRedisTemplate redis;
    private final StreamKeys streamKeys;
    private final RedisScript<Long> promoteScript;

    public DueSetPromoter(StringRedisTemplate redis, StreamKeys streamKeys) {
        this.redis = redis;
        this.streamKeys = streamKeys;
        this.promoteScript = new DefaultRedisScript<>(PROMOTE_LUA, Long.class);
    }

    /**
     * @param sortedSetKey the set to drain
     * @param now          the cutoff; members scored at or before this are due
     * @param limit        the most members to move in one call
     * @param source       the {@code source} field stamped on every entry this call writes, which
     *                     is how the worker later tells a retry from a user-scheduled first run
     * @return how many members were moved
     */
    public int promote(String sortedSetKey, Instant now, int limit, EnqueueSource source) {
        List<String> keys = new ArrayList<>();
        keys.add(sortedSetKey);
        keys.addAll(streamKeys.all());

        List<String> args = new ArrayList<>();
        args.add(Long.toString(now.toEpochMilli()));
        args.add(Integer.toString(limit));
        Priority.STRICT_ORDER.forEach(priority -> args.add(priority.name()));
        args.add(Priority.DEFAULT.name());
        args.add(source.name());

        Long moved = redis.execute(promoteScript, keys, args.toArray());
        return moved == null ? 0 : moved.intValue();
    }
}
