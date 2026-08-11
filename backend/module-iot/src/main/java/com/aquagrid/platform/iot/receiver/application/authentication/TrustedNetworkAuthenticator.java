package com.aquagrid.platform.iot.receiver.application.authentication;

import com.aquagrid.platform.iot.receiver.application.security.IpAllowListService;
import com.aquagrid.platform.iot.receiver.domain.model.InboundPacket;
import com.aquagrid.platform.iot.receiver.infrastructure.config.ReceiverProperties;
import com.aquagrid.platform.iot.receiver.spi.PacketAuthenticator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/**
 * The last word on a packet that presented no credential at all.
 *
 * <p>Sorts last, and must: {@link #supports} returns true unconditionally, so any earlier position
 * would let it pre-empt a real credential and quietly downgrade every authenticated packet in the
 * deployment to network trust.
 *
 * <p>What it decides is whether "arrived over a network we control" counts as authentication. There
 * are deployments where it legitimately does — a carrier APN or a private VPN where the meters
 * physically cannot reach anything else, and where the devices are too constrained to sign anything
 * — and pretending otherwise would only push operators into disabling security wholesale. So it is
 * expressible, narrowly:
 *
 * <ul>
 *   <li>{@code require-authentication: true} (the default) — no credential, no packet. Full stop.</li>
 *   <li>{@code false} <b>and</b> an IP allow-list configured — the allow-list is the control, and
 *       it has already been checked.</li>
 *   <li>{@code false} with no allow-list — accepted, and logged at startup as what it is: an open
 *       ingestion endpoint. Legitimate for a laptop, never for production.</li>
 * </ul>
 *
 * <p>A transport listed in {@code signature-required} is refused here regardless. That setting is a
 * statement that the bearer cannot be trusted — a UDP datagram on the public internet — and network
 * trust is exactly what it is declaring insufficient.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrustedNetworkAuthenticator implements PacketAuthenticator {

    private final ReceiverProperties properties;
    private final IpAllowListService ipAllowList;

    @Override
    public String scheme() {
        return "TRUSTED_NETWORK";
    }

    /** Always the fallback: by the time the chain reaches here, no credential was recognised. */
    @Override
    public boolean supports(InboundPacket packet) {
        return true;
    }

    @Override
    public AuthenticationResult authenticate(InboundPacket packet) {
        if (properties.security().requiresSignature(packet.transport())) {
            return AuthenticationResult.failure(
                    packet.transport() + " requires a signed payload");
        }
        if (properties.security().requireAuthentication()) {
            return AuthenticationResult.failure("No credentials presented");
        }
        // The allow-list was enforced before the chain ran; reaching here means the source passed
        // it, or that no list is configured at all.
        String principal = ipAllowList.isEnforced()
                ? "network:" + packet.sourceIp()
                : "anonymous";
        return AuthenticationResult.success(principal);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
