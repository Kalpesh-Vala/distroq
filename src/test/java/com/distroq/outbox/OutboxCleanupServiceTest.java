package com.distroq.outbox;

import com.distroq.TestProperties;
import com.distroq.config.DistroqProperties;
import com.distroq.model.OutboxEvent;
import com.distroq.model.OutboxStatus;
import com.distroq.model.ReliabilityActionType;
import com.distroq.reliability.ReliabilityAuditService;
import com.distroq.repository.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxCleanupServiceTest {

    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    private final ReliabilityAuditService audit = mock(ReliabilityAuditService.class);

    @Test
    void oldPublishedEventsAreDeletedAndCounted() {
        OutboxCleanupService service = service(TestProperties.defaults());
        List<OutboxEvent> published = published(3);
        when(repository.deletablePublished(any(), any(), anyInt())).thenReturn(published);

        OutboxCleanupService.CleanupResult result = service.cleanup("retention sweep", "operator");

        assertThat(result.deleted()).isEqualTo(3);
        assertThat(result.batchFull()).isFalse();
        verify(repository).deleteAll(published);
    }

    @Test
    void everyDeletedEventGetsItsOwnCleanupAuditRow() {
        OutboxCleanupService service = service(TestProperties.defaults());
        List<OutboxEvent> published = published(2);
        when(repository.deletablePublished(any(), any(), anyInt())).thenReturn(published);

        service.cleanup("retention sweep", "operator");

        verify(audit, times(2)).record(eq(ReliabilityActionType.OUTBOX_CLEANUP), eq("OutboxEvent"),
                any(UUID.class), eq("retention sweep"), eq("operator"), anyString(), eq("deleted"));
    }

    @Test
    void theAuditRowDescribesTheStateAndNotThePayload() {
        OutboxCleanupService service = service(TestProperties.defaults());
        OutboxEvent event = published(1).getFirst();
        when(repository.deletablePublished(any(), any(), anyInt())).thenReturn(List.of(event));

        service.cleanup("retention sweep", "operator");

        ArgumentCaptor<String> before = ArgumentCaptor.forClass(String.class);
        verify(audit).record(any(), anyString(), any(UUID.class), anyString(), anyString(),
                before.capture(), anyString());
        assertThat(before.getValue()).contains("status=PUBLISHED").doesNotContain("secret");
    }

    @Test
    void aFullBatchIsReportedSoTheOperatorKnowsToRunAgain() {
        OutboxCleanupService service = service(TestProperties.defaults());
        when(repository.deletablePublished(any(), any(), anyInt()))
                .thenReturn(published(TestProperties.OUTBOX.cleanupBatchSize()));

        OutboxCleanupService.CleanupResult result = service.cleanup("retention sweep", "operator");

        assertThat(result.deleted()).isEqualTo(TestProperties.OUTBOX.cleanupBatchSize());
        assertThat(result.batchFull()).isTrue();
    }

    @Test
    void theCleanupBatchSizeIsPassedToTheQueryRatherThanTrimmedAfterwards() {
        OutboxCleanupService service = service(TestProperties.defaults());
        when(repository.deletablePublished(any(), any(), anyInt())).thenReturn(List.of());

        service.cleanup("retention sweep", "operator");

        verify(repository).deletablePublished(any(), any(),
                eq(TestProperties.OUTBOX.cleanupBatchSize()));
    }

    /**
     * The dedupe marker outliving the retention window is the case that matters: deleting the row
     * first would leave nothing to stop a republication once the marker also expired.
     */
    @Test
    void theRetentionWindowIsTheLongerOfTheConfiguredWindowAndTheDedupeMarkerTtl() {
        DistroqProperties shortRetention = TestProperties.of(
                new DistroqProperties.Outbox(500L, 100, 30_000L, 100,
                        Duration.ofDays(14).toMillis(), 1, 90, 3_600_000L, 500, true, false));

        assertThat(service(shortRetention).retentionWindow()).isEqualTo(Duration.ofDays(14));
    }

    @Test
    void aLongRetentionWindowWinsOverAShortDedupeMarker() {
        DistroqProperties longRetention = TestProperties.of(
                new DistroqProperties.Outbox(500L, 100, 30_000L, 100, Duration.ofDays(1).toMillis(),
                        30, 90, 3_600_000L, 500, true, false));

        assertThat(service(longRetention).retentionWindow()).isEqualTo(Duration.ofDays(30));
    }

    private OutboxCleanupService service(DistroqProperties properties) {
        return new OutboxCleanupService(repository, audit, properties);
    }

    private static List<OutboxEvent> published(int count) {
        List<OutboxEvent> events = new ArrayList<>();
        IntStream.range(0, count).forEach(index -> {
            UUID id = UUID.randomUUID();
            OutboxEvent event = OutboxEvent.create(id, UUID.randomUUID(),
                    OutboxEventType.ENQUEUE_SUBMIT, "{\"secret\":\"" + id + "\"}", Instant.now());
            event.markPublished(Instant.now().minus(Duration.ofDays(60)));
            assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
            events.add(event);
        });
        return events;
    }
}
