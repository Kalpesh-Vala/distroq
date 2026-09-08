package com.distroq.queue;

import com.distroq.TestProperties;
import com.distroq.model.Priority;
import com.distroq.worker.WorkerMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PendingEntryRecoveryTest {

    @Test
    void reclaimedDeliveryUsesADedicatedConsumerIdentityEndToEnd() {
        JobStreamConsumer consumer = mock(JobStreamConsumer.class);
        DeliveryHandler handler = mock(DeliveryHandler.class);
        StreamDelivery delivery = mock(StreamDelivery.class);
        when(delivery.streamKey()).thenReturn("distroq:jobs:stream:high");
        when(delivery.entryId()).thenReturn("1-0");
        when(consumer.newConsumerName()).thenReturn("worker-recovery");
        when(consumer.claimStale(eq("worker-recovery"), any(Priority.class), any(),
                anyInt(), anyString()))
                .thenReturn(new JobStreamConsumer.ClaimedBatch("0-0", 0, List.of()));
        when(consumer.claimStale(eq("worker-recovery"), eq(Priority.HIGH), any(),
                anyInt(), anyString()))
                .thenReturn(new JobStreamConsumer.ClaimedBatch("0-0", 1, List.of(delivery)));

        PendingEntryRecovery recovery = new PendingEntryRecovery(consumer, handler,
                new StreamKeys(TestProperties.defaults()), mock(StringRedisTemplate.class),
                mock(WorkerMetrics.class), TestProperties.defaults());
        recovery.sweep();

        verify(consumer).claimStale(eq("worker-recovery"), eq(Priority.HIGH), any(),
                anyInt(), eq("0-0"));
        verify(handler).handle(delivery, "worker-recovery");
    }
}