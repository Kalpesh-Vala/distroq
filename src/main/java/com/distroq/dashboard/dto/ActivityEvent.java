package com.distroq.dashboard.dto;

import java.time.Instant;

/**
 * One line of the activity feed.
 *
 * <p>Reconstructed from database rows, never from log files: a browser reading application logs
 * would need a log-reading endpoint, and a log-reading endpoint is a much larger security surface
 * than a dashboard should introduce. The consequence is that the feed shows transitions that left
 * a durable trace, which is most of them but not all — a job that was submitted and succeeded
 * between two polls shows both, because both timestamps are columns.
 *
 * <p>{@code type} uses the {@code Events} names so a line here and a log line for the same
 * transition can be matched by eye.
 */
public record ActivityEvent(String type,
                            Instant at,
                            String jobId,
                            String eventId,
                            String jobType,
                            String priority,
                            String status,
                            String detail) {
}
