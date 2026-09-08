package com.distroq.outbox;

import com.distroq.model.Job;
import com.distroq.model.OutboxEvent;
import com.distroq.queue.EnqueueSource;
import com.distroq.repository.OutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class OutboxService {

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;

    public OutboxService(OutboxEventRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public OutboxEvent create(Job job, OutboxEventType type, EnqueueSource source, Instant dueAt) {
        UUID eventId = UUID.randomUUID();
        OutboxPayload payload = new OutboxPayload(eventId, job.getId(), job.getPriority(), source,
                dueAt, job.getScheduledAt());
        try {
            return repository.save(OutboxEvent.create(eventId, job.getId(), type,
                    objectMapper.writeValueAsString(payload), Instant.now()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize outbox event " + eventId, e);
        }
    }
}