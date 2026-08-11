package com.aquagrid.platform.iot.receiver.application.resolver;

import com.aquagrid.platform.iot.domain.model.Device;
import com.aquagrid.platform.iot.infrastructure.persistence.DeviceRepository;
import com.aquagrid.platform.iot.receiver.domain.model.IdentifierType;
import com.aquagrid.platform.iot.receiver.domain.model.ReceptionContext;
import com.aquagrid.platform.iot.receiver.spi.DeviceResolutionStrategy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Uses the device binding an authenticator already proved.
 *
 * <p>First in the chain, because it is the only strategy resting on evidence rather than on a
 * claim. When a device token or an HMAC signature authenticated the packet, the authenticator had
 * to find the device to check the credential — so the identity is settled, and re-deriving it from
 * whatever the payload asserts would be strictly worse: slower, and willing to believe a different
 * answer than the one that was verified.
 *
 * <p>That last point is the security content. Without this strategy a packet could authenticate as
 * device A and then, through a DevEUI in its body, be filed against device B. Preferring the proved
 * binding closes that gap by construction.
 */
@Component
@RequiredArgsConstructor
public class ProvedDeviceStrategy implements DeviceResolutionStrategy {

    public static final int ORDER = 10;

    private final DeviceRepository deviceRepository;

    @Override
    public String name() {
        return "PROVED_IDENTITY";
    }

    @Override
    public Set<IdentifierType> supportedIdentifiers() {
        return Set.of(IdentifierType.DEVICE_TOKEN);
    }

    @Override
    public Optional<Device> resolve(ReceptionContext context) {
        // The value is the device's own id: DeviceTokenAuthenticator puts it there after a
        // successful lookup, precisely so this stage is a primary-key read rather than a re-search.
        String deviceId = context.identifier(IdentifierType.DEVICE_TOKEN);
        if (deviceId == null || deviceId.isBlank()) {
            return Optional.empty();
        }
        try {
            return deviceRepository.findById(UUID.fromString(deviceId));
        } catch (IllegalArgumentException notAUuid) {
            // Only an authenticator writes this identifier, and only ever as a UUID. Anything else
            // means a transport populated it from the wire, where it would be an attacker-supplied
            // value — so it is ignored rather than searched for.
            return Optional.empty();
        }
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
