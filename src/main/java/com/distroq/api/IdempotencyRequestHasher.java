package com.distroq.api;

import com.distroq.model.Priority;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

@Component
public class IdempotencyRequestHasher {

    private final ObjectMapper objectMapper;

    public IdempotencyRequestHasher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String hash(String type, String payload, int maxAttempts, Priority priority,
                       Instant scheduledAt) {
        CanonicalSubmission canonical = new CanonicalSubmission(type, payload, maxAttempts,
                priority.name(), scheduledAt == null ? null : scheduledAt.toString());
        try {
            byte[] serialized = objectMapper.writeValueAsBytes(canonical);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(serialized));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Could not hash the canonical submission", e);
        }
    }

    public byte[] canonicalBytes(String type, String payload, int maxAttempts, Priority priority,
                                 Instant scheduledAt) {
        String hash = hash(type, payload, maxAttempts, priority, scheduledAt);
        return hash.getBytes(StandardCharsets.US_ASCII);
    }

    private record CanonicalSubmission(String type, String payload, int maxAttempts,
                                       String priority, String scheduledAt) {
    }
}