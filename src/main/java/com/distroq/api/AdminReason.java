package com.distroq.api;

import com.distroq.api.error.ApiException;
import com.distroq.api.error.ErrorCode;
import com.distroq.config.DistroqProperties;
import org.springframework.stereotype.Component;

/**
 * Validates {@code X-Admin-Reason} and {@code X-Admin-Actor}.
 *
 * <p>Neither header is authorization. From v1.0 that job belongs to
 * {@link AdminAuthenticationFilter}, which has already run by the time anything here is called: the
 * caller is known to hold the administrative token, and these headers say what they were doing and
 * who they claim to be. The reason is a forcing function for the audit trail, and the audit trail
 * is what makes an unexplained repair visible after the fact.
 *
 * <p>The actor is self-declared and must be read that way. One shared token cannot tell two
 * operators apart, so {@code reliability_actions.actor} records a claim made by a token holder
 * rather than a verified identity. See SECURITY.md.
 */
@Component
public class AdminReason {

    static final int MAX_ACTOR_LENGTH = 255;

    private final int maxLength;
    private final String defaultActor;

    public AdminReason(DistroqProperties properties) {
        this.maxLength = properties.admin().maxReasonLength();
        this.defaultActor = properties.admin().defaultActor();
    }

    public int maxLength() {
        return maxLength;
    }

    public String defaultActor() {
        return defaultActor;
    }

    public String requireHeader(String raw) {
        return require(raw, "X-Admin-Reason");
    }

    public String requireBody(String raw) {
        return require(raw, "reason");
    }

    /**
     * @return the declared actor, or the configured default. An over-long or control-character
     *         actor is rejected rather than truncated, because a silently shortened actor reads
     *         like a different one in the audit table.
     */
    public String actorOrDefault(String raw) {
        if (raw == null || raw.isBlank()) {
            return defaultActor;
        }
        String actor = raw.trim();
        if (actor.length() > MAX_ACTOR_LENGTH) {
            throw new ApiException(ErrorCode.INVALID_ADMIN_ACTOR,
                    "X-Admin-Actor must be at most " + MAX_ACTOR_LENGTH + " characters, got "
                            + actor.length());
        }
        for (int i = 0; i < actor.length(); i++) {
            if (Character.isISOControl(actor.charAt(i))) {
                throw new ApiException(ErrorCode.INVALID_ADMIN_ACTOR,
                        "X-Admin-Actor must not contain control characters");
            }
        }
        return actor;
    }

    private String require(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(ErrorCode.MISSING_ADMIN_REASON,
                    field + " is required and must not be blank");
        }
        String reason = raw.trim();
        if (reason.length() > maxLength) {
            throw new ApiException(ErrorCode.INVALID_ADMIN_REASON,
                    field + " must be at most " + maxLength + " characters, got " + reason.length());
        }
        return reason;
    }
}
