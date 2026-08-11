package com.aquagrid.platform.security.jwt;

import com.aquagrid.platform.common.error.BusinessException;
import com.aquagrid.platform.common.error.ErrorCode;
import com.aquagrid.platform.security.core.AuthClaims;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Mints the two kinds of JWT this platform issues.
 *
 * <p><b>Access token</b> — the API credential. Carries identity, tenant, roles and permissions so
 * that authorisation is fully stateless.
 *
 * <p><b>MFA token</b> — issued after a successful password step when a second factor is still
 * outstanding. It is a distinct token type ({@code typ=mfa}) with a five-minute life and no roles
 * or permissions, and the security filter chain refuses it everywhere except the MFA challenge
 * endpoint. Reusing the access token for this purpose — a common shortcut — would hand out a fully
 * privileged credential for passing only the first factor, which defeats MFA entirely.
 */
@Slf4j
@Service
public class JwtTokenService {

    private final JwtProperties properties;
    private final JwtKeyProvider keyProvider;
    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final Clock clock;

    public JwtTokenService(JwtProperties properties,
                           JwtKeyProvider keyProvider,
                           JwtDecoder decoder,
                           Clock clock) {
        this.properties = properties;
        this.keyProvider = keyProvider;
        this.decoder = decoder;
        this.clock = clock;
        this.encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(keyProvider.getJwkSet()));
    }

    public IssuedToken issueAccessToken(AccessTokenRequest request) {
        Instant now = clock.instant();
        Instant expiry = now.plus(properties.accessTokenTtl());
        String tokenId = UUID.randomUUID().toString();

        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .audience(List.of(properties.audience()))
                .subject(request.userId().toString())
                .id(tokenId)
                .issuedAt(now)
                .notBefore(now)
                .expiresAt(expiry)
                .claim(AuthClaims.TYPE, AuthClaims.TYPE_ACCESS)
                .claim(AuthClaims.ORGANIZATION_ID, request.organizationId().toString())
                .claim(AuthClaims.ORGANIZATION_CODE, request.organizationCode())
                .claim(AuthClaims.USERNAME, request.username())
                .claim(AuthClaims.EMAIL, request.email())
                .claim(AuthClaims.ROLES, new ArrayList<>(request.roles()))
                .claim(AuthClaims.PERMISSIONS, new ArrayList<>(request.permissions()));

        if (request.fullName() != null) {
            claims.claim(AuthClaims.FULL_NAME, request.fullName());
        }
        if (request.sessionId() != null) {
            claims.claim(AuthClaims.SESSION_ID, request.sessionId().toString());
        }
        if (request.mustChangePassword()) {
            claims.claim(AuthClaims.MUST_CHANGE_PASSWORD, true);
        }

        return new IssuedToken(encode(claims.build()), tokenId, now, expiry);
    }

    /** Intermediate credential valid only for the MFA challenge endpoint. */
    public IssuedToken issueMfaToken(UUID userId, UUID organizationId) {
        Instant now = clock.instant();
        Instant expiry = now.plus(properties.mfaTokenTtl());
        String tokenId = UUID.randomUUID().toString();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .audience(List.of(properties.audience()))
                .subject(userId.toString())
                .id(tokenId)
                .issuedAt(now)
                .notBefore(now)
                .expiresAt(expiry)
                .claim(AuthClaims.TYPE, AuthClaims.TYPE_MFA)
                .claim(AuthClaims.ORGANIZATION_ID, organizationId.toString())
                .build();

        return new IssuedToken(encode(claims), tokenId, now, expiry);
    }

    /**
     * Validates a token that the filter chain has not already processed — used by the MFA
     * challenge endpoint, which is reached anonymously.
     *
     * @throws BusinessException {@code AUTH_TOKEN_INVALID} / {@code AUTH_TOKEN_EXPIRED}
     */
    public Jwt decodeMfaToken(String token) {
        Jwt jwt;
        try {
            jwt = decoder.decode(token);
        } catch (JwtException e) {
            log.debug("MFA token rejected: {}", e.getMessage());
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID,
                    "The verification session is invalid or has expired. Please sign in again.");
        }
        if (!AuthClaims.TYPE_MFA.equals(jwt.getClaimAsString(AuthClaims.TYPE))) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID,
                    "The verification session is invalid. Please sign in again.");
        }
        return jwt;
    }

    private String encode(JwtClaimsSet claims) {
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256)
                .keyId(keyProvider.getKeyId())
                .build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
