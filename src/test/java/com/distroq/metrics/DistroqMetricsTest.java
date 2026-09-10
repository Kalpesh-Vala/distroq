package com.distroq.metrics;

import com.distroq.TestProperties;
import com.distroq.config.DistroqProperties;
import com.distroq.model.Job;
import com.distroq.model.Priority;
import com.distroq.outbox.OutboxEventType;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Metric names are a contract and metric labels are a memory-management problem.
 *
 * <p>The name tests exist because a rename silently breaks every dashboard that reads it and
 * nothing in the build would otherwise notice. The label tests exist because an unbounded label is
 * the standard way a well-behaved service turns into an out-of-memory incident three weeks after
 * release, and the only value here that a caller controls is the job type.
 */
class DistroqMetricsTest {

    private MeterRegistry registry;
    private DistroqMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new DistroqMetrics(registry, TestProperties.defaults());
    }

    @Test
    void theCounterNamesAreTheDocumentedOnes() {
        Job job = job("report", Priority.NORMAL);

        metrics.jobSubmitted(job);
        metrics.jobStarted(job);
        metrics.jobSucceeded(job, Duration.ofMillis(5));
        metrics.jobFailed(job, Duration.ofMillis(5));
        metrics.jobDeadLettered(job);
        metrics.jobReplayed(job);
        metrics.jobsReclaimed(Priority.HIGH, 2);

        assertThat(names()).contains(
                "distroq.jobs.submitted",
                "distroq.jobs.started",
                "distroq.jobs.succeeded",
                "distroq.jobs.failed",
                "distroq.jobs.dead_lettered",
                "distroq.jobs.replayed",
                "distroq.jobs.reclaimed",
                "distroq.job.attempts",
                "distroq.job.execution.duration");
    }

    @Test
    void theTimerNamesAreTheDocumentedOnes() {
        Job job = job("report", Priority.LOW);

        metrics.queueDelay(job, Duration.ofSeconds(2));
        metrics.scheduleDelay(job, Duration.ofSeconds(3));
        metrics.outboxPublished(OutboxEventType.ENQUEUE_SUBMIT, Duration.ofMillis(7));

        assertThat(names()).contains(
                "distroq.job.queue.delay",
                "distroq.job.schedule.delay",
                "distroq.outbox.publish.duration");
    }

    @Test
    void everyMeterIsNamespacedSoNothingCollidesWithAFrameworkMetric() {
        metrics.jobSubmitted(job("report", Priority.HIGH));

        assertThat(names()).allMatch(name -> name.startsWith("distroq."));
    }

    @Test
    void noJobIdEverBecomesALabel() {
        Job job = job("report", Priority.NORMAL);

        metrics.jobSubmitted(job);
        metrics.jobStarted(job);
        metrics.jobSucceeded(job, Duration.ofMillis(1));

        assertThat(tagValues()).doesNotContain(job.getId().toString());
    }

    @Test
    void theOnlyLabelsAreBoundedEnumerationsAndTheCappedJobType() {
        Job job = job("report", Priority.NORMAL);
        metrics.jobSubmitted(job);
        metrics.jobSucceeded(job, Duration.ofMillis(1));
        metrics.outboxPublished(OutboxEventType.ENQUEUE_SUBMIT, Duration.ofMillis(1));

        Set<String> keys = registry.getMeters().stream()
                .flatMap(meter -> meter.getId().getTags().stream())
                .map(Tag::getKey)
                .collect(Collectors.toSet());

        assertThat(keys).containsOnly("priority", "jobType", "outcome", "eventType");
    }

    @Test
    void aJobTypeChosenByTheCallerCannotGrowTheSeriesCountWithoutBound() {
        DistroqMetrics capped = new DistroqMetrics(registry, TestProperties.builder()
                .metrics(new DistroqProperties.Metrics(true, 3, 5_000L))
                .build());

        for (int i = 0; i < 500; i++) {
            capped.jobSubmitted(job("order-" + UUID.randomUUID(), Priority.NORMAL));
        }

        List<Meter> submitted = registry.getMeters().stream()
                .filter(meter -> meter.getId().getName().equals("distroq.jobs.submitted"))
                .toList();

        assertThat(submitted).hasSizeLessThanOrEqualTo(4);
        assertThat(tagValues()).contains(BoundedTagValues.OVERFLOW);
    }

    @Test
    void theJobTypeLabelCanBeSwitchedOffEntirely() {
        DistroqMetrics untagged = new DistroqMetrics(registry, TestProperties.builder()
                .metrics(new DistroqProperties.Metrics(false, 20, 5_000L))
                .build());

        untagged.jobSubmitted(job("report", Priority.NORMAL));

        assertThat(registry.getMeters().stream()
                .flatMap(meter -> meter.getId().getTags().stream())
                .map(Tag::getKey))
                .doesNotContain("jobType");
    }

    @Test
    void aNegativeDurationIsClampedRatherThanCorruptingTheHistogramSum() {
        Job job = job("report", Priority.NORMAL);

        metrics.queueDelay(job, Duration.ofSeconds(-30));

        assertThat(registry.find("distroq.job.queue.delay").timer().totalTime(
                java.util.concurrent.TimeUnit.SECONDS)).isZero();
    }

    @Test
    void aSuccessAndAFailureAreDistinguishedByOutcomeRatherThanByMetricName() {
        Job job = job("report", Priority.NORMAL);

        metrics.jobSucceeded(job, Duration.ofMillis(1));
        metrics.jobFailed(job, Duration.ofMillis(1));

        assertThat(registry.find("distroq.job.attempts").tag("outcome", "SUCCEEDED").counter()
                .count()).isEqualTo(1d);
        assertThat(registry.find("distroq.job.attempts").tag("outcome", "FAILED").counter()
                .count()).isEqualTo(1d);
    }

    private Set<String> names() {
        return registry.getMeters().stream()
                .map(meter -> meter.getId().getName())
                .collect(Collectors.toSet());
    }

    private Set<String> tagValues() {
        return registry.getMeters().stream()
                .flatMap(meter -> meter.getId().getTags().stream())
                .map(Tag::getValue)
                .collect(Collectors.toSet());
    }

    private static Job job(String type, Priority priority) {
        return Job.create(type, "{}", 3, priority, (Instant) null);
    }
}
