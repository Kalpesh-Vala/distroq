package com.distroq.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;
import java.util.UUID;

/**
 * One row per job that has exhausted its retries. Keyed by {@code jobId} rather than a
 * surrogate id, so a job replayed and dead-lettered again updates its row instead of
 * accumulating duplicates — which is what keeps {@code replayCount} meaningful.
 *
 * <p>Holds the raw {@code jobId} rather than a {@code @ManyToOne}, consistent with
 * {@link JobAttempt}.
 */
@Entity
@Table(name = "dead_letters")
public class DeadLetter {

    @Id
    @Column(name = "job_id")
    private UUID jobId;

    @Column(columnDefinition = "text")
    private String finalError;

    @Column(nullable = false)
    private Instant movedAt;

    @ColumnDefault("false")
    @Column(nullable = false)
    private boolean replayed;

    private Instant replayedAt;

    @ColumnDefault("0")
    @Column(nullable = false)
    private int replayCount;

    protected DeadLetter() {
        // for JPA
    }

    public static DeadLetter of(UUID jobId, String finalError) {
        DeadLetter deadLetter = new DeadLetter();
        deadLetter.jobId = jobId;
        deadLetter.finalError = finalError;
        deadLetter.movedAt = Instant.now();
        deadLetter.replayed = false;
        deadLetter.replayCount = 0;
        return deadLetter;
    }

    /**
     * Re-dead-lettering after a replay. Refreshes the failure and clears the replayed flag but
     * preserves {@code replayCount} and {@code replayedAt}, so the history of how often this job
     * has been sent back around is not lost.
     */
    public void markDeadLetteredAgain(String finalError) {
        this.finalError = finalError;
        this.movedAt = Instant.now();
        this.replayed = false;
    }

    /** The row is retained rather than deleted: "has this job ever been dead-lettered" stays answerable. */
    public void markReplayed() {
        this.replayed = true;
        this.replayedAt = Instant.now();
        this.replayCount++;
    }

    public UUID getJobId() {
        return jobId;
    }

    public String getFinalError() {
        return finalError;
    }

    public Instant getMovedAt() {
        return movedAt;
    }

    public boolean isReplayed() {
        return replayed;
    }

    public Instant getReplayedAt() {
        return replayedAt;
    }

    public int getReplayCount() {
        return replayCount;
    }
}
