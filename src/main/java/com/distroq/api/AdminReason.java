package com.distroq.api;

import com.distroq.config.DistroqProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Validates {@code X-Admin-Reason}.
 *
 * <p>This is not authorization and must not be read as any. v0.8 has no authentication at all:
 * anything that can reach the port can call these endpoints, and the header only guarantees that
 * whoever did left a sentence in {@code reliability_actions} explaining themselves. It is a
 * forcing function for the audit trail, and the audit trail is what makes an unexplained repair
 * visible after the fact. Real authentication is deferred — see README.md.
 */
@Component
public class AdminReason {

    private final int maxLength;

    public AdminReason(DistroqProperties properties) {
        this.maxLength = properties.admin().maxReasonLength();
    }

    public int maxLength() {
        return maxLength;
    }

    public String requireHeader(String raw) {
        return require(raw, "X-Admin-Reason");
    }

    public String requireBody(String raw) {
        return require(raw, "reason");
    }

    private String require(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    field + " is required and must not be blank");
        }
        String reason = raw.trim();
        if (reason.length() > maxLength) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    field + " must be at most " + maxLength + " characters, got " + reason.length());
        }
        return reason;
    }
}
