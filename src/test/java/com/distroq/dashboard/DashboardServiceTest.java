package com.distroq.dashboard;

import com.distroq.TestProperties;
import com.distroq.config.DistroqProperties;
import com.distroq.dashboard.dto.DashboardResponse;
import com.distroq.dashboard.dto.OutboxView;
import com.distroq.dashboard.dto.PageView;
import com.distroq.dashboard.dto.QueueView;
import com.distroq.dashboard.dto.SystemView;
import com.distroq.dashboard.dto.WorkerView;
import com.distroq.model.Job;
import com.distroq.model.OutboxEvent;
import com.distroq.model.OutboxStatus;
import com.distroq.model.Priority;
import com.distroq.observability.LogFields;
import com.distroq.outbox.OutboxEventType;
import com.distroq.reliability.ReliabilityMetrics;
import com.distroq.repository.DeadLetterRepository;
import com.distroq.repository.JobAttemptRepository;
import com.distroq.repository.JobEffectRepository;
import com.distroq.repository.JobRepository;
import com.distroq.repository.ReliabilityActionRepository;
import com.distroq.worker.WorkerMetrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.MDC;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The dashboard's aggregation rules, without a database or a Redis.
 *
 * <p>Four properties are pinned here because each one is a way the dashboard could quietly become
 * dangerous: showing an unavailable dependency as zero, echoing a payload or an error body,
 * serving an unbounded page, and losing the correlation ID that ties a wrong number to the log
 * line that explains it.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DashboardServiceTest {

    private static final DashboardProperties PROPERTIES = new DashboardProperties(
            true, 5000L, 50, 200, 100, 86_400_000L, 30_000L, 60_000L, "analytics/output",
            "dashboard/dist", 5000L);

    private static final SystemView SYSTEM = new SystemView("1.1.0", "abc", "abc", "main", null,
            null, null, "local", "host-1", "21", "OpenJDK 21", "3.5.16", "7.2.4", "16.4", "8",
            "dashboard read indexes", 0, "CORRECT", "ACCEPTING_TRAFFIC", Instant.now(), 1000L,
            null, 5000L, true);

    private final DistroqProperties distroq = TestProperties.defaults();

    @Mock private DashboardQueries queries;
    @Mock private QueueInspector queueInspector;
    @Mock private AnalyticsReportReader analytics;
    @Mock private ReconciliationSnapshotCache reconciliationCache;
    @Mock private ReadinessJournal readinessJournal;
    @Mock private RuntimeInfo runtimeInfo;
    @Mock private HealthProbe healthProbe;
    @Mock private ReliabilityMetrics reliabilityMetrics;
    @Mock private WorkerMetrics workerMetrics;
    @Mock private JobRepository jobs;
    @Mock private JobAttemptRepository attempts;
    @Mock private JobEffectRepository effects;
    @Mock private DeadLetterRepository deadLetters;
    @Mock private ReliabilityActionRepository reliabilityActions;

    private DashboardService service;

    @BeforeEach
    void setUp() {
        service = new DashboardService(PROPERTIES, distroq, queries, queueInspector, analytics,
                reconciliationCache, readinessJournal, runtimeInfo, healthProbe, reliabilityMetrics,
                workerMetrics, jobs, attempts, effects, deadLetters, reliabilityActions);

        when(runtimeInfo.describe()).thenReturn(SYSTEM);
        when(reliabilityMetrics.snapshot(any())).thenReturn(Map.of(
                "reconciliationFindings", 2L, "reconciliationRepairs", 1L,
                "staleScheduledJobs", 0L, "staleRetryJobs", 0L,
                "expiredExecutionLeases", 0L, "staleEffects", 0L,
                "outboxRetryableFailed", 3L));
        when(queries.outboxCountsByStatus()).thenReturn(counts());
        when(queries.recentlyChangedJobs(anyInt())).thenReturn(List.of());
        when(queries.recentOutboxEvents(anyInt())).thenReturn(List.of());
        when(queries.recentAbandonedAttempts(anyInt())).thenReturn(List.of());
        when(queries.recentDeadLetters(anyInt())).thenReturn(List.of());
        when(queries.recentReliabilityActions(anyInt())).thenReturn(List.of());
        when(readinessJournal.recent()).thenReturn(List.of());
    }

    @AfterEach
    void clearContext() {
        MDC.clear();
    }

    @Test
    void aRedisOutageCostsTheRedisPanelAndLeavesTheRestStanding() {
        when(queueInspector.streams()).thenThrow(
                new RedisConnectionFailureException("redis://:hunter2@cache:6379 unreachable"));

        DashboardResponse.Overview overview = service.overview();

        assertThat(overview.queues().availability()).isEqualTo(Section.UNAVAILABLE);
        assertThat(overview.queues().data()).isNull();
        assertThat(overview.outbox().availability()).isEqualTo(Section.AVAILABLE);
        assertThat(overview.deadLetters().availability()).isEqualTo(Section.AVAILABLE);
        assertThat(overview.system().availability()).isEqualTo(Section.AVAILABLE);
    }

    @Test
    void anUnavailablePanelNeverLeaksTheConnectionStringThatFailed() {
        when(queueInspector.streams()).thenThrow(
                new RedisConnectionFailureException("redis://:hunter2@cache:6379 unreachable"));

        DashboardResponse.Overview overview = service.overview();

        assertThat(overview.queues().reason()).isEqualTo("RedisConnectionFailureException");
        assertThat(overview.toString()).doesNotContain("hunter2").doesNotContain("redis://");
    }

    @Test
    void anUnknowableStreamLagIsNullRatherThanZero() {
        // Redis reports a null lag once entries have been trimmed away; a zero here would read as
        // "nothing is waiting", which is the opposite of "we cannot tell"
        when(queueInspector.streams()).thenReturn(List.of(
                new QueueInspector.StreamSnapshot("HIGH", "distroq:jobs:stream:high", 12L, 4L, 1L,
                        1000L, List.of()),
                new QueueInspector.StreamSnapshot("NORMAL", "distroq:jobs:stream:normal", 7L, null,
                        0L, null, List.of())));
        when(queueInspector.scheduled()).thenReturn(emptySortedSet());
        when(queueInspector.delayed()).thenReturn(emptySortedSet());

        QueueView.Totals totals = service.queues().queues().data().totals();

        assertThat(totals.readyDepth()).isNull();
        assertThat(totals.readyDepthByPriority().get("NORMAL")).isNull();
        assertThat(totals.streamDepth()).isEqualTo(19L);
        assertThat(totals.pendingEntries()).isEqualTo(1L);
    }

    @Test
    void streamLengthIsNeverPresentedAsExecutableDepth() {
        when(queueInspector.streams()).thenReturn(List.of(
                new QueueInspector.StreamSnapshot("HIGH", "distroq:jobs:stream:high", 5000L, 0L, 0L,
                        null, List.of())));
        when(queueInspector.scheduled()).thenReturn(emptySortedSet());
        when(queueInspector.delayed()).thenReturn(emptySortedSet());

        DashboardResponse.Queues queues = service.queues();

        assertThat(queues.queues().data().priorities().get(0).streamLength()).isEqualTo(5000L);
        assertThat(queues.queues().data().priorities().get(0).readyDepth()).isZero();
        assertThat(queues.note()).contains("not equivalent to the number of jobs waiting");
    }

    @Test
    void anOutboxErrorIsRedactedAndNoPayloadFieldExists() {
        OutboxEvent event = OutboxEvent.create(UUID.randomUUID(), UUID.randomUUID(),
                OutboxEventType.ENQUEUE_SUBMIT, "{\"secret\":\"customer-account-12345\"}",
                Instant.now());
        event.markFailed("upstream said: " + "z".repeat(1000), 1);
        when(queries.outboxEvents(any(), any(), any(), anyInt(), anyInt(), any(), anyBoolean()))
                .thenReturn(new DashboardQueries.Slice<>(List.of(event), 1, 0, 50));
        when(queries.recentPublicationLatenciesMs()).thenReturn(List.of(10L, 20L, 30L));
        when(queries.outboxCountsByEventType()).thenReturn(Map.of());
        when(queries.outboxUnpublishedByAge(any())).thenReturn(Map.of());

        OutboxView view = service.outbox(null, null, null, null, null, null, null)
                .outbox().data();
        OutboxView.EventRow row = view.events().content().get(0);

        assertThat(row.payloadRedacted()).isTrue();
        assertThat(row.lastError()).endsWith("… (truncated)");
        assertThat(view.toString()).doesNotContain("customer-account-12345");
    }

    @Test
    void anOverLargePageRequestIsClampedToTheConfiguredMaximum() {
        when(queries.outboxEvents(any(), any(), any(), anyInt(), anyInt(), any(), anyBoolean()))
                .thenReturn(new DashboardQueries.Slice<>(List.of(), 0, 0, 200));
        when(queries.recentPublicationLatenciesMs()).thenReturn(List.of());
        when(queries.outboxCountsByEventType()).thenReturn(Map.of());
        when(queries.outboxUnpublishedByAge(any())).thenReturn(Map.of());

        PageView<OutboxView.EventRow> page = service
                .outbox(null, null, null, -5, 1_000_000, null, null).outbox().data().events();

        assertThat(page.size()).isEqualTo(200);
        assertThat(page.page()).isZero();
        verify(queries).outboxEvents(any(), any(), any(), org.mockito.ArgumentMatchers.eq(0),
                org.mockito.ArgumentMatchers.eq(200), any(), anyBoolean());
    }

    @Test
    void aJobRowHasNoPayloadFieldAtAll() {
        Job job = Job.create("send_email", "{\"to\":\"person@example.com\"}", 3, Priority.HIGH);
        when(queries.jobs(any(), anyInt(), anyInt(), any(), anyBoolean()))
                .thenReturn(new DashboardQueries.Slice<>(List.of(job), 1, 0, 50));
        when(queries.jobTypes(anyInt())).thenReturn(List.of("send_email"));
        when(queries.jobCountsByStatus()).thenReturn(Map.of("QUEUED", 1L));

        DashboardResponse.Jobs response = service.jobs(null, null, null, null, null, null, null,
                null, null, null, null, null);

        assertThat(response.jobs().data().content()).hasSize(1);
        assertThat(response.toString()).doesNotContain("person@example.com");
        assertThat(java.util.Arrays.stream(
                        com.distroq.dashboard.dto.JobView.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName))
                .doesNotContain("payload");
    }

    @Test
    void theCorrelationIdOfTheRequestTravelsIntoEveryResponse() {
        MDC.put(LogFields.CORRELATION_ID, "abc-123");
        when(queueInspector.streams()).thenReturn(List.of());
        when(queueInspector.scheduled()).thenReturn(emptySortedSet());
        when(queueInspector.delayed()).thenReturn(emptySortedSet());

        assertThat(service.overview().correlationId()).isEqualTo("abc-123");
        assertThat(service.queues().correlationId()).isEqualTo("abc-123");
        assertThat(service.activity(null).correlationId()).isEqualTo("abc-123");
    }

    @Test
    void aLeaseHeartbeatIsUnknownRatherThanHealthyWhenRedisCannotBeAsked() {
        when(queueInspector.streams()).thenThrow(new RedisConnectionFailureException("down"));
        when(queries.activeLeases(any(), anyInt()))
                .thenReturn(List.of(Job.create("report", "{}", 3, Priority.LOW)));

        DashboardResponse.Workers workers = service.workers();

        assertThat(workers.consumers().availability()).isEqualTo(Section.UNAVAILABLE);
        assertThat(workers.leases().availability()).isEqualTo(Section.AVAILABLE);
        WorkerView.LeaseRow lease = workers.leases().data().get(0);
        assertThat(lease.heartbeatStale()).isNull();
        assertThat(workers.note()).contains("execution lease");
    }

    @Test
    void anAnalyticsDeploymentThatHasNeverExportedIsNotConfiguredRatherThanEmpty() {
        when(analytics.read(any())).thenReturn(java.util.Optional.empty());

        Section<?> section = service.analytics(null, null, null, null, null).analytics();

        assertThat(section.availability()).isEqualTo(Section.NOT_CONFIGURED);
        assertThat(section.data()).isNull();
    }

    @Test
    void theDlqViewCarriesNoReplayAffordanceAndSaysWhereReplayLives() {
        when(queries.deadLetterFacts(anyInt())).thenReturn(List.of());
        when(queries.deadLetters(any(), anyInt(), anyInt()))
                .thenReturn(new DashboardQueries.Slice<>(List.of(), 0, 0, 50));

        DashboardResponse.Dlq dlq = service.dlq(null, null, null);

        assertThat(dlq.note()).contains("administrative API");
        assertThat(java.util.Arrays.stream(
                        com.distroq.dashboard.dto.DlqView.EntryRow.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName))
                .doesNotContain("replayUrl", "retryUrl");
    }

    @Test
    void nothingTheDashboardDoesAsksForARepair() {
        when(queueInspector.streams()).thenReturn(List.of());
        when(queueInspector.scheduled()).thenReturn(emptySortedSet());
        when(queueInspector.delayed()).thenReturn(emptySortedSet());
        when(queries.deadLetterFacts(anyInt())).thenReturn(List.of());
        when(queries.deadLetters(any(), anyInt(), anyInt()))
                .thenReturn(new DashboardQueries.Slice<>(List.of(), 0, 0, 50));

        service.overview();
        service.queues();
        service.dlq(null, null, null);
        service.activity(null);

        // the only repository handles the service holds are read-only interfaces, and the two
        // write-capable collaborators are never asked to save anything
        verify(jobs, never()).save(any());
        verify(deadLetters, never()).save(any());
        verify(attempts, never()).save(any());
        verify(effects, never()).save(any());
        verify(reliabilityActions, never()).save(any());
    }

    @Test
    void percentilesOverAnEmptySampleAreNullRatherThanZero() {
        assertThat(DashboardService.percentile(List.of(), 95)).isNull();
        assertThat(DashboardService.percentile(null, 50)).isNull();
    }

    @Test
    void percentilesUseTheNearestRankOfTheSample() {
        List<Long> sample = List.of(50L, 10L, 40L, 20L, 30L);

        assertThat(DashboardService.percentile(sample, 50)).isEqualTo(30L);
        assertThat(DashboardService.percentile(sample, 95)).isEqualTo(50L);
        assertThat(DashboardService.percentile(List.of(7L), 95)).isEqualTo(7L);
    }

    private static QueueInspector.SortedSetSnapshot emptySortedSet() {
        Map<String, Long> byPriority = new LinkedHashMap<>();
        Priority.STRICT_ORDER.forEach(priority -> byPriority.put(priority.name(), 0L));
        return new QueueInspector.SortedSetSnapshot(0L, byPriority);
    }

    private static Map<String, Long> counts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (OutboxStatus status : OutboxStatus.values()) {
            counts.put(status.name(), 0L);
        }
        return counts;
    }
}
