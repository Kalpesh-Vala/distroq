package com.distroq.api.dto;

import com.distroq.model.DeadLetter;
import com.distroq.model.Job;
import com.distroq.model.JobAttempt;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Single DLQ entry with the full attempt history, so the whole failure story is one call. */
public record DeadLetterDetailResponse(
        UUID jobId,
        String type,
        String payload,
        String status,
        String priority,
        int attemptCount,
        int maxAttempts,
        String finalError,
        Instant movedAt,
        boolean replayed,
        Instant replayedAt,
        int replayCount,
        List<AttemptResponse> attempts) {

    public static DeadLetterDetailResponse from(DeadLetter deadLetter, Job job, List<JobAttempt> attempts) {
        return new DeadLetterDetailResponse(
                deadLetter.getJobId(),
                job.getType(),
                job.getPayload(),
                job.getStatus().name(),
                job.getPriority().name(),
                job.getAttemptCount(),
                job.getMaxAttempts(),
                deadLetter.getFinalError(),
                deadLetter.getMovedAt(),
                deadLetter.isReplayed(),
                deadLetter.getReplayedAt(),
                deadLetter.getReplayCount(),
                attempts.stream().map(AttemptResponse::from).toList());
    }
}
