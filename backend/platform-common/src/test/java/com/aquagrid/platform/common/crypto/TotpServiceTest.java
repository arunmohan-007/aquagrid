package com.aquagrid.platform.common.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TotpServiceTest {

    private final TotpService service = new TotpService();

    /** RFC 6238 Appendix B, HMAC-SHA1, seed "12345678901234567890" truncated to six digits. */
    private static final String RFC_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    @ParameterizedTest(name = "T={0} yields {1}")
    @CsvSource({
            "59,          287082",
            "1111111109,  081804",
            "1111111111,  050471",
            "1234567890,  005924",
            "2000000000,  279037"
    })
    @DisplayName("matches the RFC 6238 reference vectors")
    void matchesRfcVectors(long epochSecond, String expectedCode) {
        assertThat(service.verifyAt(RFC_SECRET, expectedCode, Instant.ofEpochSecond(epochSecond)))
                .isTrue();
    }

    @Test
    @DisplayName("accepts a code from the adjacent step to absorb clock skew")
    void acceptsAdjacentStep() {
        // The code valid at T=1111111109 must still be accepted 30 seconds later.
        assertThat(service.verifyAt(RFC_SECRET, "081804", Instant.ofEpochSecond(1111111109L + 30)))
                .isTrue();
        assertThat(service.verifyAt(RFC_SECRET, "081804", Instant.ofEpochSecond(1111111109L - 30)))
                .isTrue();
    }

    @Test
    @DisplayName("rejects a code two steps away — the window is not open-ended")
    void rejectsDistantStep() {
        assertThat(service.verifyAt(RFC_SECRET, "081804", Instant.ofEpochSecond(1111111109L + 90)))
                .isFalse();
    }

    @Test
    @DisplayName("rejects malformed input without throwing")
    void rejectsMalformedInput() {
        Instant now = Instant.ofEpochSecond(59);
        assertThat(service.verifyAt(RFC_SECRET, null, now)).isFalse();
        assertThat(service.verifyAt(RFC_SECRET, "", now)).isFalse();
        assertThat(service.verifyAt(RFC_SECRET, "12345", now)).isFalse();
        assertThat(service.verifyAt(RFC_SECRET, "1234567", now)).isFalse();
        assertThat(service.verifyAt(RFC_SECRET, "abcdef", now)).isFalse();
        assertThat(service.verifyAt("not-base32!", "287082", now)).isFalse();
    }

    @Test
    @DisplayName("generates a 160-bit secret and a well-formed provisioning URI")
    void generatesSecretAndUri() {
        String secret = service.generateSecret();
        assertThat(Base32.decode(secret)).hasSize(TotpService.SECRET_BYTES);

        String uri = service.buildProvisioningUri("AquaGrid", "j.mathew@kwa.gov.in", secret);
        assertThat(uri)
                .startsWith("otpauth://totp/AquaGrid:")
                .contains("secret=" + secret)
                .contains("issuer=AquaGrid")
                .contains("algorithm=SHA1")
                .contains("digits=6")
                .contains("period=30");
    }

    @Test
    @DisplayName("Base32 round-trips arbitrary byte content")
    void base32RoundTrip() {
        byte[] original = TokenGenerator.randomKey(20);
        assertThat(Base32.decode(Base32.encode(original))).isEqualTo(original);
    }
}
