package com.distroq.dashboard.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A v0.9 analytics run, read from the files it wrote.
 *
 * <p>{@code live} is false and {@code exact} is false, and both are rendered next to the numbers.
 * These are batch aggregates over a closed window: they are exact about that window and say
 * nothing at all about the last five minutes. A chart that does not admit that is a chart an
 * operator will use to conclude the queue is fine during an outage.
 *
 * <p>{@code availableRuns} is a list of window identifiers, not paths. The dashboard resolves a
 * requested run by matching it against this list rather than by joining it onto a directory.
 */
public record AnalyticsView(String runId,
                            List<String> availableRuns,
                            Instant windowStart,
                            Instant windowEnd,
                            Instant generatedAt,
                            Instant exportedAt,
                            String analyticsVersion,
                            String applicationVersion,
                            double durationSeconds,
                            boolean live,
                            boolean exact,
                            String basis,
                            Map<String, Object> headline,
                            Map<String, Object> factRowCounts,
                            Map<String, Object> dataQuality,
                            Map<String, List<Map<String, Object>>> reports,
                            Filters filters) {

    /** Echoed back so the UI can show what the numbers were narrowed to. */
    public record Filters(String startDate, String endDate, String priority, String jobType) {
    }
}
