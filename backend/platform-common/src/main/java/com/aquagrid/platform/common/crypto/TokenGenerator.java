package com.aquagrid.platform.common.crypto;

import java.security.SecureRandom;
import java.util.Base64;

/** Generates the opaque, high-entropy secrets used for refresh tokens and reset links. */
public final class TokenGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final char[] RECOVERY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    private TokenGenerator() {
    }

    /** 256 bits of entropy, URL-safe Base64 (43 characters). */
    public static String opaqueToken() {
        return randomBytes(32);
    }

    public static String randomBytes(int byteCount) {
        byte[] buffer = new byte[byteCount];
        RANDOM.nextBytes(buffer);
        return URL_ENCODER.encodeToString(buffer);
    }

    /**
     * MFA recovery code in {@code XXXXX-XXXXX} form.
     *
     * <p>The alphabet excludes I, O, 0 and 1 — these codes are read off a screen and typed by hand,
     * often under stress, and transcription errors are the dominant failure mode.
     */
    public static String recoveryCode() {
        StringBuilder builder = new StringBuilder(11);
        for (int i = 0; i < 10; i++) {
            if (i == 5) {
                builder.append('-');
            }
            builder.append(RECOVERY_ALPHABET[RANDOM.nextInt(RECOVERY_ALPHABET.length)]);
        }
        return builder.toString();
    }

    public static byte[] randomKey(int byteCount) {
        byte[] buffer = new byte[byteCount];
        RANDOM.nextBytes(buffer);
        return buffer;
    }
}
