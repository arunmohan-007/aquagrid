package com.aquagrid.platform.iot.receiver.application.authentication;

import com.aquagrid.platform.iot.receiver.domain.model.InboundPacket;
import com.aquagrid.platform.iot.receiver.domain.model.PacketCredentials;
import com.aquagrid.platform.iot.receiver.spi.PacketAuthenticator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Authenticates a bearer JWT presented by an edge service or a first-party bridge.
 *
 * <p>Not for devices. A meter has no way to obtain, refresh or safely store a JWT, and a
 * long-lived one issued to hardware is a credential that cannot be revoked. This scheme exists for
 * software senders inside the estate — an edge collector, a migration job, the simulator running
 * against a remote environment — where the platform's own token infrastructure already applies.
 *
 * <p>It reuses the platform's {@link JwtDecoder}, which is pinned to RS256 and validated against
 * the JWKS. Verifying the token here with anything else would create a second trust root for the
 * same signing key, and the two would drift on the day the key rotates.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BearerTokenAuthenticator implements PacketAuthenticator {

    public static final int ORDER = 40;

    private final JwtDecoder jwtDecoder;

    @Override
    public String scheme() {
        return "BEARER";
    }

    @Override
    public boolean supports(InboundPacket packet) {
        return packet.credentials().has(PacketCredentials.Keys.BEARER_TOKEN);
    }

    @Override
    public AuthenticationResult authenticate(InboundPacket packet) {
        String token = packet.credentials().get(PacketCredentials.Keys.BEARER_TOKEN);
        try {
            Jwt jwt = jwtDecoder.decode(token);
            String subject = jwt.getSubject() == null ? "unknown" : jwt.getSubject();

            // The tenant claim is carried forward as a hint only. It is never the tenant of record:
            // that is always the resolved device's organisation, or a service token would be able
            // to file readings against a device belonging to somebody else.
            java.util.UUID tenant = null;
            Object orgClaim = jwt.getClaims().get("org");
            if (orgClaim != null) {
                try {
                    tenant = java.util.UUID.fromString(String.valueOf(orgClaim));
                } catch (IllegalArgumentException ignored) {
                    // A malformed claim is not worth refusing the packet over — the tenant it
                    // would have hinted at is derived from the device anyway.
                }
            }
            return AuthenticationResult.success("jwt:" + subject, Map.of(), tenant);
        } catch (JwtException e) {
            return AuthenticationResult.failure("Invalid bearer token");
        }
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
