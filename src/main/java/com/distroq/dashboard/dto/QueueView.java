package com.distroq.dashboard.dto;

import java.util.List;
import java.util.Map;

/**
 * Queue state, with the four counts kept apart.
 *
 * <p>They are separate fields with separate names because they are separate facts, and the single
 * most common way to misread a Redis Stream is to treat {@code XLEN} as a backlog. It is not:
 * a stream keeps acknowledged entries, so {@code streamLength} only ever grows and a stream with
 * ten thousand entries and nothing waiting is a normal, healthy stream.
 *
 * <ul>
 *   <li>{@code streamLength} — {@code XLEN}. Every entry the stream has ever held. History.</li>
 *   <li>{@code readyDepth} — the consumer group's {@code lag}. Entries the group has never been
 *       offered. This is the executable backlog, and null when Redis cannot derive it.</li>
 *   <li>{@code pendingEntries} — delivered and not acknowledged. In flight, plus anything
 *       abandoned and not yet reclaimed.</li>
 *   <li>{@code scheduledCount} / {@code delayedCount} — sorted-set members waiting on a time. Not
 *       stream state at all, and not backlog: no worker could run them yet.</li>
 * </ul>
 */
public record QueueView(List<PriorityRow> priorities, Totals totals) {

    public record PriorityRow(String priority,
                              String streamKey,
                              long streamLength,
                              Long readyDepth,
                              long pendingEntries,
                              long scheduledCount,
                              long delayedCount,
                              Long oldestPendingEntryAgeMs,
                              int activeConsumers,
                              List<ConsumerRow> consumers) {
    }

    public record ConsumerRow(String consumerName,
                              String streamKey,
                              String priority,
                              long pendingCount,
                              long idleTimeMs,
                              boolean idle) {
    }

    public record Totals(Map<String, Long> streamDepthByPriority,
                         Map<String, Long> readyDepthByPriority,
                         Map<String, Long> pendingEntriesByPriority,
                         Map<String, Long> scheduledDepthByPriority,
                         Map<String, Long> delayedDepthByPriority,
                         long streamDepth,
                         Long readyDepth,
                         long pendingEntries,
                         long scheduledDepth,
                         long delayedDepth,
                         int activeConsumers) {
    }

    /**
     * Recent movement per tier, from the jobs table rather than from Redis.
     *
     * <p>Approximate and labelled so. {@code startedAt} holds a job's most recent start rather
     * than each one, so a job retried inside the window counts once. The exact history is the
     * v0.9 analytics pipeline's job.
     */
    public record ThroughputRow(String priority,
                                long submitted,
                                long started,
                                long succeeded,
                                long deadLettered,
                                Double successRate) {
    }
}
