package com.distroq.effects;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 of an effect result.
 *
 * <p>The ledger stores this instead of the result itself. It is enough to prove that two
 * observations of the same effect agree, and it cannot leak a response body, a token, or a
 * customer record into a table that operators read casually and that outlives the job.
 */
public final class ResponseHash {

    private ResponseHash() {
    }

    public static String of(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
    }
}
