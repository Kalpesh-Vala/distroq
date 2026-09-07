package com.distroq.queue;

import com.distroq.config.DistroqProperties;
import com.distroq.model.Priority;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The one place that turns a {@link Priority} into a Redis Stream key.
 *
 * <p>Four components need these keys — the initializer, the consumer, the recovery sweep and the
 * metrics endpoint — and a fifth builds them in Lua. Hardcoding them in each is how the set drifts
 * out of sync with the enum, so they are derived once from the configured base plus
 * {@link Priority#keySuffix()}, exactly as the v0.4 list keys were.
 *
 * <p>The reverse mapping exists because {@code XREADGROUP} over several streams answers with the
 * key it served from, and that is how the caller learns which tier it got.
 */
@Component
public class StreamKeys {

    private final Map<Priority, String> byPriority;
    private final Map<String, Priority> byKey;
    private final List<String> allKeys;

    public StreamKeys(DistroqProperties properties) {
        Map<Priority, String> keys = new EnumMap<>(Priority.class);
        Map<String, Priority> priorities = new LinkedHashMap<>();
        for (Priority priority : Priority.STRICT_ORDER) {
            String key = properties.streamKey() + ':' + priority.keySuffix();
            keys.put(priority, key);
            priorities.put(key, priority);
        }
        this.byPriority = Map.copyOf(keys);
        this.byKey = Map.copyOf(priorities);
        this.allKeys = List.copyOf(priorities.keySet());
    }

    public String keyFor(Priority priority) {
        return byPriority.get(Priority.orDefault(priority));
    }

    /** Empty for a key this application does not own, rather than a silent default. */
    public Optional<Priority> priorityFor(String streamKey) {
        return Optional.ofNullable(byKey.get(streamKey));
    }

    /** Every stream key, in strict priority order. */
    public List<String> all() {
        return allKeys;
    }
}
