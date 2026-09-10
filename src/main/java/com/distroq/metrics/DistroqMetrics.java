package com.distroq.metrics;

import com.distroq.config.DistroqProperties;
import com.distroq.model.Job;
import com.distroq.model.Priority;
import com.distroq.outbox.OutboxEventType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Every process-local counter and timer DistroQ publishes, and the only place allowed to name one.
 *
 * <p>Names are Micrometer's dotted form; Prometheus renders them with underscores and the
 * {@code _total} suffix a counter earns automatically, so {@code distroq.jobs.submitted} is
 * scraped as {@code distroq_jobs_submitted_total}. The mapping is mechanical, which is why the
 * documented Prometheus names and the code here look different but never drift.
 *
 * <p>Labels are bounded by construction. Priority is a three-valued enum, outcome and status are
 * enums, event type is an enum, and job type — the one value a caller controls — goes through
 * {@link BoundedTagValues}. No job ID, attempt ID, outbox event ID, idempotency key, payload or
 * token is ever a label: those are per-request values, and a per-request label is an unbounded
 * time series.
 *
 * <p>Everything here is process-local and resets on restart. The database-derived gauges, which do
 * not, live in {@link DistroqGauges}.
 */
@Component
public class DistroqMetrics {

    private static final String TAG_PRIORITY = "priority";
    private static final String TAG_TYPE = "jobType";
    private static final String TAG_OUTCOME = "outcome";
    private static final String TAG_EVENT_TYPE = "eventType";

    private final MeterRegistry registry;
    private final BoundedTagValues jobTypes;
    private final boolean jobTypeTagEnabled;

    public DistroqMetrics(MeterRegistry registry, DistroqProperties properties) {
        this.registry = registry;
        this.jobTypeTagEnabled = properties.metrics().jobTypeTag();
        this.jobTypes = new BoundedTagValues(properties.metrics().maxJobTypeTags());
    }

    public void jobSubmitted(Job job) {
        counter("distroq.jobs.submitted", job).increment();
    }

    public void jobStarted(Job job) {
        counter("distroq.jobs.started", job).increment();
    }

    public void jobSucceeded(Job job, Duration executionTime) {
        counter("distroq.jobs.succeeded", job).increment();
        attempt(job, "SUCCEEDED");
        executionTimer(job, "SUCCEEDED").record(executionTime);
    }

    public void jobFailed(Job job, Duration executionTime) {
        counter("distroq.jobs.failed", job).increment();
        attempt(job, "FAILED");
        executionTimer(job, "FAILED").record(executionTime);
    }

    public void jobDeadLettered(Job job) {
        counter("distroq.jobs.dead_lettered", job).increment();
    }

    public void jobReplayed(Job job) {
        counter("distroq.jobs.replayed", job).increment();
    }

    public void jobsReclaimed(Priority tier, int count) {
        if (count > 0) {
            Counter.builder("distroq.jobs.reclaimed")
                    .description("Stream entries taken from an idle consumer by XAUTOCLAIM")
                    .tag(TAG_PRIORITY, tier.name())
                    .register(registry)
                    .increment(count);
        }
    }

    /** Time between the job becoming runnable and a worker starting it. */
    public void queueDelay(Job job, Duration delay) {
        record(Timer.builder("distroq.job.queue.delay")
                .description("Delay between a job becoming runnable and a worker claiming it")
                .tags(TAG_PRIORITY, job.getPriority().name()), delay);
    }

    /** Time between the instant the submitter asked for and the job actually starting. */
    public void scheduleDelay(Job job, Duration delay) {
        record(Timer.builder("distroq.job.schedule.delay")
                .description("Delay between a user-requested execution time and execution")
                .tags(TAG_PRIORITY, job.getPriority().name()), delay);
    }

    public void outboxPublished(OutboxEventType eventType, Duration duration) {
        record(Timer.builder("distroq.outbox.publish.duration")
                .description("Time to publish one outbox event to Redis")
                .tags(TAG_EVENT_TYPE, name(eventType), TAG_OUTCOME, "PUBLISHED"), duration);
    }

    public void outboxPublishFailed(OutboxEventType eventType, Duration duration,
                                    boolean terminal) {
        record(Timer.builder("distroq.outbox.publish.duration")
                .description("Time to publish one outbox event to Redis")
                .tags(TAG_EVENT_TYPE, name(eventType), TAG_OUTCOME,
                        terminal ? "TERMINAL_FAILURE" : "RETRYABLE_FAILURE"), duration);
    }

    private void attempt(Job job, String outcome) {
        Counter.builder("distroq.job.attempts")
                .description("Execution attempts finalised, by outcome")
                .tags(tags(job, TAG_OUTCOME, outcome))
                .register(registry)
                .increment();
    }

    private Timer executionTimer(Job job, String outcome) {
        return Timer.builder("distroq.job.execution.duration")
                .description("Wall-clock time spent executing a job attempt")
                .tags(tags(job, TAG_OUTCOME, outcome))
                .register(registry);
    }

    private Counter counter(String name, Job job) {
        return Counter.builder(name)
                .tags(tags(job))
                .register(registry);
    }

    private void record(Timer.Builder builder, Duration duration) {
        // a negative duration means the clocks disagree, not that time ran backwards; recording it
        // would drag the histogram's sum below zero and make every rate on it meaningless
        builder.register(registry).record(duration.isNegative() ? Duration.ZERO : duration);
    }

    private String[] tags(Job job, String... extra) {
        String[] tags = new String[(jobTypeTagEnabled ? 4 : 2) + extra.length];
        int i = 0;
        tags[i++] = TAG_PRIORITY;
        tags[i++] = job.getPriority() == null ? BoundedTagValues.UNKNOWN : job.getPriority().name();
        if (jobTypeTagEnabled) {
            tags[i++] = TAG_TYPE;
            tags[i++] = jobTypes.valueFor(job.getType());
        }
        System.arraycopy(extra, 0, tags, i, extra.length);
        return tags;
    }

    private static String name(OutboxEventType eventType) {
        return eventType == null ? BoundedTagValues.UNKNOWN : eventType.name();
    }
}
