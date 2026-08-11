package com.aquagrid.platform.iot.receiver.application.authentication;

import com.aquagrid.platform.iot.receiver.application.metrics.ReceiverMetrics;
import com.aquagrid.platform.iot.receiver.domain.model.InboundPacket;
import com.aquagrid.platform.iot.receiver.spi.PacketAuthenticator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Runs the authenticator chain and returns the first verdict.
 *
 * <p>First-match rather than try-them-all, and the difference is a security property rather than a
 * performance one. If a packet presenting a bad signature could fall through to be accepted by a
 * weaker scheme — or by network trust — then presenting a deliberately invalid signature would be a
 * way to <em>downgrade</em> the check. So the first authenticator that recognises the credential
 * owns the decision, and its refusal is final.
 *
 * <p>The chain is assembled from every {@link PacketAuthenticator} bean in the context, sorted by
 * order. Adding a scheme — an Azure IoT Hub SAS token, an OPC-UA certificate — is one new bean;
 * nothing here changes, and nothing else in the pipeline learns that schemes exist.
 */
@Slf4j
@Service
public class ReceiverAuthenticationService {

    private final List<PacketAuthenticator> authenticators;
    private final ReceiverMetrics metrics;

    public ReceiverAuthenticationService(List<PacketAuthenticator> authenticators,
                                         ReceiverMetrics metrics) {
        List<PacketAuthenticator> ordered = new java.util.ArrayList<>(authenticators);
        ordered.sort(AnnotationAwareOrderComparator.INSTANCE);
        this.authenticators = List.copyOf(ordered);
        this.metrics = metrics;
        log.info("Receiver authentication chain: {}",
                this.authenticators.stream().map(PacketAuthenticator::scheme).toList());
    }

    /**
     * @return the verdict of the first authenticator that recognises the packet's credentials.
     *         The chain always terminates: {@code TrustedNetworkAuthenticator} supports everything
     *         and sorts last, so there is no path out of here without a decision
     */
    public Verdict authenticate(InboundPacket packet) {
        for (PacketAuthenticator authenticator : authenticators) {
            if (!authenticator.supports(packet)) {
                continue;
            }
            PacketAuthenticator.AuthenticationResult result = authenticator.authenticate(packet);
            if (!result.authenticated()) {
                metrics.recordAuthenticationFailure(packet.transport(), authenticator.scheme(),
                        result.failure() == null ? "UNKNOWN" : result.failure().name());
                // Debug, not warn. On a public ingestion endpoint failed authentication is
                // ordinary traffic; logging every one at warn is how an attacker fills the disk.
                log.debug("Packet {} rejected by {}: {}", packet.packetId(),
                        authenticator.scheme(), result.detail());
            }
            return new Verdict(authenticator.scheme(), result);
        }
        // Unreachable while the fallback authenticator is registered — but a deployment that
        // excluded it would otherwise fail open, which is the one outcome that must be impossible.
        return new Verdict("NONE", PacketAuthenticator.AuthenticationResult
                .failure("No authenticator accepted the packet"));
    }

    public record Verdict(String scheme, PacketAuthenticator.AuthenticationResult result) {

        public boolean authenticated() {
            return result.authenticated();
        }
    }
}
