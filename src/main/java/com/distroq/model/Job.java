package com.distroq.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "jobs")
public class Job {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String type;

    @Column(columnDefinition = "text")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobStatus status;

    /**
     * Immutable after submission: there is no setter and no re-prioritise endpoint, because the
     * ID is already committed to a tier-specific Redis list and moving it would be a non-atomic
     * remove-then-push. See NOTES.md.
     */
    @Enumerated(EnumType.STRING)
    @ColumnDefault("'NORMAL'")
    @Column(nullable = false)
    private Priority priority;

    private int attemptCount;

    // DB-level default so ddl-auto: update can add this NOT NULL column to existing v0.1 rows
    @ColumnDefault("3")
    @Column(nullable = false)
    private int maxAttempts;

    @Column(columnDefinition = "text")
    private String errorMessage;

    private Instant createdAt;
    private Instant updatedAt;
    private Instant startedAt;
    private Instant finishedAt;
    private Instant nextAttemptAt;

    /**
     * The execution time the submitter asked for, or null for an immediate job.
     *
     * <p>Historical metadata, not working state. It is set once at submission and is never
     * cleared: not by promotion, not by a retry, not by dead-lettering, not by replay. Retry
     * timing lives in {@link #nextAttemptAt} and the two are deliberately not the same field —
     * overloading one would make "when was this job asked for" unanswerable the moment it failed
     * once. Immutable in v0.6: there is no setter and no reschedule endpoint.
     */
    private Instant scheduledAt;

    protected Job() {
        // for JPA
    }

    /** Pre-v0.4 signature, retained so that submitting without a priority is provably unchanged. */
    public static Job create(String type, String payload, int maxAttempts) {
        return create(type, payload, maxAttempts, null);
    }

    /** Pre-v0.6 signature: no requested execution time, so the job starts QUEUED as it always did. */
    public static Job create(String type, String payload, int maxAttempts, Priority priority) {
        return create(type, payload, maxAttempts, priority, null);
    }

    /**
     * A null {@code priority} resolves to {@link Priority#DEFAULT}; absence is not an error.
     *
     * <p>{@code scheduledAt} decides the starting status, and it is the only place that decision
     * is made. A time in the future starts the job {@link JobStatus#SCHEDULED}; null, or a time
     * already past, starts it {@link JobStatus#QUEUED} and it is enqueued straight away. A past
     * timestamp is not an error — it is a request that is already due — and parking it in the
     * scheduled set only to promote it on the next tick would add latency for nothing.
     */
    public static Job create(String type, String payload, int maxAttempts, Priority priority,
                             Instant scheduledAt) {
        Instant now = Instant.now();
        Job job = new Job();
        job.id = UUID.randomUUID();
        job.type = type;
        job.payload = payload;
        job.status = isFuture(scheduledAt, now) ? JobStatus.SCHEDULED : JobStatus.QUEUED;
        job.priority = Priority.orDefault(priority);
        job.attemptCount = 0;
        job.maxAttempts = maxAttempts;
        job.scheduledAt = scheduledAt;
        job.createdAt = now;
        job.updatedAt = now;
        return job;
    }

    private static boolean isFuture(Instant scheduledAt, Instant now) {
        return scheduledAt != null && scheduledAt.isAfter(now);
    }

    public boolean isScheduled() {
        return status == JobStatus.SCHEDULED;
    }

    /**
     * The job's due time has arrived and its stream entry is in hand.
     *
     * <p>Not an attempt and not a retry: {@code attemptCount} is untouched, no attempt row is
     * opened, and {@code scheduledAt} is kept. The very next thing that happens is
     * {@link #markRunning()}, so QUEUED here is a checkpoint rather than a resting state — it
     * exists so that a crash between promotion and execution leaves a runnable job rather than
     * one still claiming to be waiting for a time that has passed.
     *
     * <p>Throws on any other status. Nothing else in this class can produce SCHEDULED, so a job
     * that has already run, succeeded, failed or been dead-lettered cannot be walked back into
     * the scheduling path by a duplicate delivery.
     */
    public void markQueuedFromSchedule() {
        if (status != JobStatus.SCHEDULED) {
            throw new IllegalStateException(
                    "Only a SCHEDULED job can be queued from its schedule; job " + id + " is " + status);
        }
        this.status = JobStatus.QUEUED;
        this.updatedAt = Instant.now();
    }

    public boolean hasAttemptsRemaining() {
        return attemptCount < maxAttempts;
    }

    public void markRunning() {
        Instant now = Instant.now();
        this.status = JobStatus.RUNNING;
        this.attemptCount++;
        this.startedAt = now;
        this.updatedAt = now;
        this.nextAttemptAt = null;
    }

    public void markSucceeded() {
        Instant now = Instant.now();
        this.status = JobStatus.SUCCEEDED;
        this.finishedAt = now;
        this.updatedAt = now;
        this.nextAttemptAt = null;
    }

    /** Non-terminal: finishedAt stays unset because the job has not finished, only this attempt has. */
    public void markRetrying(String error, Instant nextAttemptAt) {
        Instant now = Instant.now();
        this.status = JobStatus.RETRYING;
        this.errorMessage = error;
        this.nextAttemptAt = nextAttemptAt;
        this.updatedAt = now;
    }

    public void markFailed(String error) {
        Instant now = Instant.now();
        this.status = JobStatus.FAILED;
        this.errorMessage = error;
        this.finishedAt = now;
        this.updatedAt = now;
        this.nextAttemptAt = null;
    }

    /** Terminal. Replaces {@code markFailed} on the exhaustion path from v0.3 onward. */
    public void markDeadLettered(String finalError) {
        Instant now = Instant.now();
        this.status = JobStatus.DEAD_LETTERED;
        this.errorMessage = finalError;
        this.finishedAt = now;
        this.updatedAt = now;
        this.nextAttemptAt = null;
    }

    /**
     * Deliberately does not touch {@code attemptCount}: a job that failed 3 times runs as attempt
     * 4, so {@code job_attempts} still reads as one coherent history. {@code maxAttempts} is
     * extended rather than reset, otherwise the replay would be out of budget before it started.
     */
    public void prepareForReplay(int additionalAttempts) {
        Instant now = Instant.now();
        this.status = JobStatus.QUEUED;
        this.maxAttempts = this.attemptCount + additionalAttempts;
        this.errorMessage = null;
        this.finishedAt = null;
        this.nextAttemptAt = null;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getType() {
        return type;
    }

    public String getPayload() {
        return payload;
    }

    public JobStatus getStatus() {
        return status;
    }

    public Priority getPriority() {
        return priority;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getScheduledAt() {
        return scheduledAt;
    }
}
