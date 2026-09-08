package com.distroq.outbox;

import com.distroq.TestProperties;
import com.distroq.model.OutboxEvent;
import com.distroq.model.Priority;
import com.distroq.queue.EnqueueSource;
import com.distroq.queue.StreamKeys;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisOutboxPublisherTest {

    private StringRedisTemplate redis;
    private ObjectMapper objectMapper;
    private RedisOutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        objectMapper = JsonMapper.builder().findAndAddModules().build();
        publisher = new RedisOutboxPublisher(redis, objectMapper,
                new StreamKeys(TestProperties.defaults()), TestProperties.defaults());
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn("1-0");
    }

    @Test
    void streamPublicationUsesEventDedupeKeyAndCarriesEventId() throws Exception {
        OutboxEvent event = event(OutboxEventType.ENQUEUE_SUBMIT, EnqueueSource.SUBMIT, null);

        publisher.publish(event);

        assertThat(capturedKeys().get(0)).isEqualTo("distroq:outbox:published:" + event.getId());
        assertThat(capturedArgs()).contains(event.getId().toString(), "SUBMIT", "NORMAL");
    }

    @Test
    void scheduledPublicationUsesAStableEventAwareMember() throws Exception {
        Instant dueAt = Instant.parse("2026-09-07T15:30:00Z");
        OutboxEvent event = event(OutboxEventType.SCHEDULE_USER_JOB, EnqueueSource.SCHEDULED, dueAt);

        publisher.publish(event);

        assertThat(capturedKeys()).containsExactly(
                "distroq:outbox:published:" + event.getId(), "distroq:jobs:scheduled");
        assertThat(capturedArgs()).contains("NORMAL:" + event.getAggregateId() + ':' + event.getId());
    }

    @Test
    void replayingAnEventUsesTheSameAtomicDedupeKey() throws Exception {
        OutboxEvent event = event(OutboxEventType.SCHEDULE_RETRY, EnqueueSource.RETRY, Instant.now());

        publisher.publish(event);
        publisher.publish(event);

        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(redis, times(2)).execute(any(RedisScript.class), keys.capture(), any(Object[].class));
        assertThat(keys.getAllValues().get(0).get(0)).isEqualTo(keys.getAllValues().get(1).get(0));
    }

    private OutboxEvent event(OutboxEventType type, EnqueueSource source, Instant dueAt)
            throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        String payload = objectMapper.writeValueAsString(new OutboxPayload(eventId, jobId,
                Priority.NORMAL, source, dueAt, dueAt));
        return OutboxEvent.create(eventId, jobId, type, payload, Instant.now());
    }

    @SuppressWarnings("unchecked")
    private List<String> capturedKeys() {
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(redis).execute(any(RedisScript.class), keys.capture(), any(Object[].class));
        return keys.getValue();
    }

    private List<Object> capturedArgs() {
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(redis).execute(any(RedisScript.class), anyList(), args.capture());
        return List.of(args.getValue());
    }
}