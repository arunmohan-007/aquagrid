package com.aquagrid.platform.iot.receiver.application.authentication;

import com.aquagrid.platform.iot.domain.model.Device;
import com.aquagrid.platform.iot.infrastructure.persistence.DeviceRepository;
import com.aquagrid.platform.iot.receiver.domain.model.IdentifierType;
import com.aquagrid.platform.iot.receiver.domain.model.InboundPacket;
import com.aquagrid.platform.iot.receiver.domain.model.PacketCredentials;
import com.aquagrid.platform.iot.receiver.spi.PacketAuthenticator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;

/**
 * Authenticates a device by a token issued to it at provisioning.
 *
 * <p>The strongest of the identity-bearing schemes, because it does both jobs at once: a device
 * token names exactly one device <em>and</em> proves the sender holds it. Everything else the
 * receiver can resolve on — a DevEUI in a body, a client id in a topic — is a claim that anyone who
 * has seen one packet can copy.
 *
 * <p>The token is looked up by SHA-256 hash, not by the token. That is why registration stores
 * {@code deviceTokenHash} as an ordinary provisioning field beside the encrypted
 * {@code secret:deviceToken}: the platform encrypts secrets with AES-GCM under a random IV, which
 * is correct for confidentiality and makes the ciphertext useless as a lookup key — the same token
 * encrypts to a different value every time. A hash is deterministic, indexable (V1403), and is not
 * itself a credential, so an attacker with read access to the device table cannot authenticate with
 * what they find there.
 *
 * <p>Because this proves the binding, the resolved device is handed back as a {@code DEVICE_TOKEN}
 * identifier and the resolver prefers it over anything the payload asserted.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceTokenAuthenticator implements PacketAuthenticator {

    /** Second only to a payload signature, which additionally proves the bytes were not altered. */
    public static final int ORDER = 20;

    /** Provisioning key holding the lookup hash. Non-secret by construction — see the class note. */
    public static final String TOKEN_HASH_FIELD = "deviceTokenHash";

    private final DeviceRepository deviceRepository;

    @Override
    public String scheme() {
        return "DEVICE_TOKEN";
    }

    @Override
    public boolean supports(InboundPacket packet) {
        return packet.credentials().has(PacketCredentials.Keys.DEVICE_TOKEN);
    }

    @Override
    @Transactional(readOnly = true)
    public AuthenticationResult authenticate(InboundPacket packet) {
        String token = packet.credentials().get(PacketCredentials.Keys.DEVICE_TOKEN);
        String hash = ApiKeyAuthenticator.sha256Hex(token);

        Optional<Device> device = deviceRepository.findFirstByProvisioningField(TOKEN_HASH_FIELD, hash);
        if (device.isEmpty()) {
            // Deliberately indistinguishable from "device exists but token is wrong". Anything
            // finer turns this endpoint into an oracle for which device tokens are live.
            return AuthenticationResult.failure("Unrecognised device token");
        }

        Device resolved = device.get();
        return AuthenticationResult.success(
                resolved.getDeviceCode(),
                Map.of(IdentifierType.DEVICE_TOKEN, resolved.getId().toString()),
                resolved.getOrganizationId());
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
