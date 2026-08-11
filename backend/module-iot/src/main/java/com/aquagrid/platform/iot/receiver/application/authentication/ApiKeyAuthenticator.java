package com.aquagrid.platform.iot.receiver.application.authentication;

import com.aquagrid.platform.iot.receiver.application.security.Sha256;
import com.aquagrid.platform.iot.receiver.domain.model.InboundPacket;
import com.aquagrid.platform.iot.receiver.domain.model.PacketCredentials;
import com.aquagrid.platform.iot.receiver.infrastructure.config.ReceiverProperties;
import com.aquagrid.platform.iot.receiver.spi.PacketAuthenticator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Authenticates a gateway or integration by its API key.
 *
 * <p>The workhorse for infrastructure senders: a ChirpStack network server, a carrier NB-IoT
 * webhook, a SCADA bridge. It proves <em>who is delivering</em>, not which device is reporting —
 * one gateway legitimately delivers for thousands of meters, so device resolution still has to
 * happen from the packet's own identifiers afterwards.
 *
 * <p>Keys are compared as SHA-256 hashes, in constant time. Both matter and for different reasons:
 * hashing means the deployment's configuration never holds a working credential, and constant-time
 * comparison means an attacker cannot recover a key a byte at a time from response timing. A
 * {@code String.equals} here would leak the key's leading bytes through exactly that channel.
 */
@Slf4j
@Component
public class ApiKeyAuthenticator implements PacketAuthenticator {

    /**
     * After signatures and device tokens. An API key proves the gateway, not the device, so a
     * packet presenting both an API key and something device-specific should be judged on the
     * stronger claim.
     */
    public static final int ORDER = 30;

    private final List<ReceiverProperties.GatewayCredential> gateways;

    public ApiKeyAuthenticator(ReceiverProperties properties) {
        this.gateways = properties.security().gateways().stream()
                .filter(g -> g.apiKeySha256() != null && !g.apiKeySha256().isBlank())
                .toList();
        if (gateways.isEmpty()) {
            log.info("No receiver gateway API keys configured — API-key authentication is inactive");
        } else {
            log.info("Receiver API-key authentication active for {} gateway credential(s): {}",
                    gateways.size(),
                    gateways.stream().map(ReceiverProperties.GatewayCredential::principal).toList());
        }
    }

    @Override
    public String scheme() {
        return "API_KEY";
    }

    @Override
    public boolean supports(InboundPacket packet) {
        return packet.credentials().has(PacketCredentials.Keys.API_KEY);
    }

    @Override
    public AuthenticationResult authenticate(InboundPacket packet) {
        String presented = packet.credentials().get(PacketCredentials.Keys.API_KEY);
        String presentedHash = sha256Hex(presented);

        // Walked in full rather than short-circuited on the first match. The list is a handful of
        // entries, and iterating all of them keeps the work independent of which key was presented
        // — a loop that exits early on a match tells a prober, through timing, roughly where in the
        // list their guess landed.
        ReceiverProperties.GatewayCredential matched = null;
        for (ReceiverProperties.GatewayCredential candidate : gateways) {
            if (constantTimeEquals(presentedHash, candidate.apiKeySha256())) {
                matched = candidate;
            }
        }

        if (matched == null) {
            return AuthenticationResult.failure("Unrecognised API key");
        }
        if (!matched.permits(packet.transport())) {
            // Scoping a credential to its transports is what stops a leaked LoRaWAN webhook key
            // from being usable to inject UDP datagrams.
            return AuthenticationResult.failure(
                    "API key for " + matched.principal() + " is not valid on " + packet.transport());
        }
        return AuthenticationResult.success(matched.principal(), Map.of(), null);
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    /**
     * Full-length SHA-256 hex — the value {@code sha256sum} produces.
     *
     * <p>Explicitly not the replay cache's digest, which truncates to 16 bytes. Borrowing that one
     * here made every configured credential fail to match, with an error message that pointed at
     * the key rather than at its length. See {@link Sha256}.
     */
    static String sha256Hex(String value) {
        return Sha256.hex(value == null ? "" : value);
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
