package com.distroq.queue;

import com.distroq.model.Priority;

import java.util.UUID;

/**
 * What a dequeue returned, and which tier it came from.
 *
 * <p>The tier is not decoration. With one list per tier the popped ID alone no longer says where
 * it came from, and the starvation guard has to count what it served — so returning a bare
 * {@code UUID} would make the counter unimplementable without a second lookup.
 */
public record Dequeued(UUID jobId, Priority tier) {
}
