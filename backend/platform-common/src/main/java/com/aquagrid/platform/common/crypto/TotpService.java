package com.aquagrid.platform.common.crypto;

import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;

/**
 * RFC 6238 TOTP, compatible with Google Authenticator, Microsoft Authenticator, Authy and 1Password.
 *
 * <p>Defaults: HMAC-SHA1, 6 digits, 30-second step. SHA-1 is specified deliberately — it is what
 * every mainstream authenticator implements, and its use inside HMAC for a 30-second one-time code
 * is not affected by SHA-1 collision attacks.
 *
 * <p>Verification accepts a ±1 step window (90 s total) to absorb clock skew between the phone and
 * the server. Widening it further materially weakens the factor.
 */
@Service
public class TotpService {

    public static final int SECRET_BYTES = 20;
    private static final int DIGITS = 6;
    private static final int PERIOD_SECONDS = 30;
    private static final int WINDOW_STEPS = 1;
    private static final int[] POWERS = {1, 10, 100, 1_000, 10_000, 100_000, 1_000_000, 10_000_000};

    /** Generates a fresh Base32 shared secret for enrolment. */
    public String generateSecret() {
        return Base32.encode(TokenGenerator.randomKey(SECRET_BYTES));
    }

    /**
     * Builds the {@code otpauth://} URI that is rendered as a QR code.
     *
     * @param issuer  product/tenant label shown in the authenticator
     * @param account normally the user's email
     */
    public String buildProvisioningUri(String issuer, String account, String base32Secret) {
        String encodedIssuer = URLEncoder.encode(issuer, StandardCharsets.UTF_8);
        String encodedAccount = URLEncoder.encode(account, StandardCharsets.UTF_8);
        return "otpauth://totp/%s:%s?secret=%s&issuer=%s&algorithm=SHA1&digits=%d&period=%d"
                .formatted(encodedIssuer, encodedAccount, base32Secret, encodedIssuer, DIGITS,
                        PERIOD_SECONDS);
    }

    public boolean verify(String base32Secret, String code) {
        return verifyAt(base32Secret, code, Instant.now());
    }

    /** Package-visible seam so the RFC 6238 test vectors can be asserted at fixed instants. */
    public boolean verifyAt(String base32Secret, String code, Instant at) {
        if (base32Secret == null || code == null) {
            return false;
        }
        String normalised = code.replaceAll("\\s", "");
        if (normalised.length() != DIGITS || !normalised.chars().allMatch(Character::isDigit)) {
            return false;
        }
        byte[] key;
        try {
            key = Base32.decode(base32Secret);
        } catch (IllegalArgumentException e) {
            return false;
        }
        long currentStep = at.getEpochSecond() / PERIOD_SECONDS;
        boolean matched = false;
        // Every candidate step is evaluated even after a match, so verification time does not
        // reveal which step succeeded.
        for (long step = currentStep - WINDOW_STEPS; step <= currentStep + WINDOW_STEPS; step++) {
            String candidate = generate(key, step);
            matched |= MessageDigest.isEqual(candidate.getBytes(StandardCharsets.UTF_8),
                    normalised.getBytes(StandardCharsets.UTF_8));
        }
        return matched;
    }

    /** Current code — used only by tests and the enrolment self-check. */
    public String currentCode(String base32Secret) {
        return generate(Base32.decode(base32Secret), Instant.now().getEpochSecond() / PERIOD_SECONDS);
    }

    private String generate(byte[] key, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(step).array());

            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);
            return String.format("%0" + DIGITS + "d", binary % POWERS[DIGITS]);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA1 is required but unavailable", e);
        }
    }
}
