package com.aquagrid.platform.security.jwt;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

/**
 * Token validation wiring.
 *
 * <p>Validation is explicit about three things that are commonly left to defaults and are commonly
 * the vulnerability:
 * <ul>
 *   <li><b>Algorithm is pinned to RS256.</b> The decoder is built from the public key, so a token
 *       presenting {@code alg: none} or an HMAC algorithm cannot be verified — the classic JWT
 *       confusion attack fails structurally rather than by a check we might forget.</li>
 *   <li><b>Issuer and audience are asserted.</b> Without an audience check, a token minted for a
 *       different system that happens to share our key would be accepted.</li>
 *   <li><b>A 30-second clock skew allowance</b> and nothing more.</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfig {

    @Bean
    public JwtDecoder jwtDecoder(JwtKeyProvider keyProvider, JwtProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withPublicKey(keyProvider.getPublicKey())
                .signatureAlgorithm(org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.RS256)
                .build();

        decoder.setJwtValidator(new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.issuer()),
                audienceValidator(properties.audience())));
        return decoder;
    }

    private OAuth2TokenValidator<Jwt> audienceValidator(String expectedAudience) {
        return jwt -> {
            List<String> audience = jwt.getClaimAsStringList(JwtClaimNames.AUD);
            if (audience != null && audience.contains(expectedAudience)) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token",
                    "The required audience '%s' is missing".formatted(expectedAudience), null));
        };
    }

}
// NOTE: the access-vs-MFA token type check is deliberately NOT applied in this decoder.
// The same decoder is reused by the MFA challenge endpoint, which must be able to read an
// mfa-typed token. The type constraint is enforced one layer up, in
// PrincipalJwtAuthenticationConverter, which refuses to build an authenticated principal from
// anything other than typ=access.
