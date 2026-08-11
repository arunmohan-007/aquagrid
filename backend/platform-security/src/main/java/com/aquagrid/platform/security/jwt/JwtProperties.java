package com.aquagrid.platform.security.jwt;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Token policy.
 *
 * @param issuer          {@code iss} claim; also the expected issuer on validation
 * @param audience        {@code aud} claim
 * @param accessTokenTtl  lifetime of the API access token. Deliberately short: it is a bearer
 *                        credential that cannot be revoked before expiry, so its lifetime <em>is</em>
 *                        the revocation window.
 * @param mfaTokenTtl     lifetime of the intermediate token issued after a successful password step
 * @param resetTokenTtl   lifetime of a password reset link
 * @param invitationTtl   lifetime of a user-invitation link (Module 2). Longer than a reset link
 *                        because an invitee may take days to accept, but still bounded so stale
 *                        invitations do not accumulate.
 * @param privateKeyPem   PKCS#8 RSA private key. Absent in development only — a key is then
 *                        generated at startup and every existing session dies on restart.
 * @param publicKeyPem    X.509 RSA public key matching {@code privateKeyPem}
 * @param keyId           {@code kid} advertised in the JWKS document; rotate by adding a new id
 */
@Validated
@ConfigurationProperties(prefix = "aquagrid.security.jwt")
public record JwtProperties(
        @NotBlank String issuer,
        @NotBlank String audience,
        @NotNull Duration accessTokenTtl,
        @NotNull Duration mfaTokenTtl,
        @NotNull Duration resetTokenTtl,
        @NotNull Duration invitationTtl,
        String privateKeyPem,
        String publicKeyPem,
        String keyId
) {
    public JwtProperties {
        accessTokenTtl = accessTokenTtl == null ? Duration.ofMinutes(15) : accessTokenTtl;
        mfaTokenTtl = mfaTokenTtl == null ? Duration.ofMinutes(5) : mfaTokenTtl;
        resetTokenTtl = resetTokenTtl == null ? Duration.ofMinutes(30) : resetTokenTtl;
        invitationTtl = invitationTtl == null ? Duration.ofDays(7) : invitationTtl;
        keyId = keyId == null || keyId.isBlank() ? "aquagrid-signing-key" : keyId;

        if (accessTokenTtl.toMinutes() > 60) {
            throw new IllegalArgumentException(
                    "aquagrid.security.jwt.access-token-ttl must not exceed 60 minutes: an access "
                            + "token cannot be revoked, so its lifetime is the revocation window");
        }
    }
}
