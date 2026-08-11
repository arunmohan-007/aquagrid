package com.aquagrid.platform.common.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Deterministic hashing for opaque lookup keys (refresh tokens, reset tokens, recovery codes).
 *
 * <p>SHA-256 — <b>not</b> BCrypt — is correct here and the distinction matters. These values are
 * 256-bit cryptographically random secrets, not human-chosen passwords: they have no exploitable
 * entropy for an offline attacker, so a slow KDF buys nothing, while a fast deterministic digest
 * lets us index the column and look the token up in one query. BCrypt would force a table scan.
 */
public final class Hashes {

    private Hashes() {
    }

    /** Returns the lowercase hex SHA-256 of {@code value} (64 characters). */
    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }

    /** Length-independent, timing-safe comparison of two hex digests. */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
