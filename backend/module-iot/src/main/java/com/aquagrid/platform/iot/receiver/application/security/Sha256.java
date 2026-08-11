package com.aquagrid.platform.iot.receiver.application.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256, at two lengths, named so the choice is never accidental.
 *
 * <p>This class exists because it was got wrong. {@link ReplayProtectionService} needs a short
 * digest — its keys are already scoped to one device inside one replay window, so 16 bytes is ample
 * and the column is an index key whose width is paid for on every insert. Credential hashing needs
 * the full digest, for a reason that has nothing to do with collision resistance: an operator
 * configuring a gateway computes the hash with {@code sha256sum}, and a truncated value simply does
 * not match what they produce. The credential is then rejected with "unrecognised API key" — a
 * message that sends them looking at the key rather than at its length.
 *
 * <p>Two methods with explicit names, rather than one that quietly truncates.
 */
public final class Sha256 {

    private Sha256() {
    }

    /** Full 64-character hex digest. What {@code sha256sum} and every other tool produces. */
    public static String hex(String value) {
        return HexFormat.of().formatHex(digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * First 16 bytes as 32 hex characters, for keys that are already narrowly scoped.
     *
     * <p>Never use this for a credential: it will not match a hash computed anywhere else.
     */
    public static String truncatedHex(byte[] value) {
        return HexFormat.of().formatHex(digest(value), 0, 16);
    }

    private static byte[] digest(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JLS for every conforming JVM.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
