package com.distroq.api.dto;

import com.distroq.model.DeadLetter;
import com.distroq.model.Job;

import java.time.Instant;
import java.util.UUID;

/**
 * DLQ listing entry. Joins to the job for {@code type}, {@code status} and {@code attemptCount} —
 * a listing of bare IDs would not tell an operator anything actionable.
 */
public record DeadLetterResponse(
        UUID jobId,
        String type,
        String status,
        String priority,
        int attemptCount,
        int maxAttempts,
        String finalError,
        Instant movedAt,
        boolean replayed,
        Instant replayedAt,
        int replayCount,
        /** The execution time originally requested, if this job was scheduled rather than immediate. */
        Instant scheduledAt) {

    public static DeadLetterResponse from(DeadLetter deadLetter, Job job) {
        return new DeadLetterResponse(
                deadLetter.getJobId(),
                job == null ? null : job.getType(),
                job == null ? null : job.getStatus().name(),
                job == null ? null : job.getPriority().name(),
                job == null ? 0 : job.getAttemptCount(),
                job == null ? 0 : job.getMaxAttempts(),
                deadLetter.getFinalError(),
                deadLetter.getMovedAt(),
                deadLetter.isReplayed(),
                deadLetter.getReplayedAt(),
                deadLetter.getReplayCount(),
                job == null ? null : job.getScheduledAt());
    }
}
