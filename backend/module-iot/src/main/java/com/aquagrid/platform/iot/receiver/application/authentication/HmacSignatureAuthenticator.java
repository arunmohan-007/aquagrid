package com.aquagrid.platform.iot.receiver.application.authentication;

import com.aquagrid.platform.common.crypto.CryptoService;
import com.aquagrid.platform.iot.domain.model.Device;
import com.aquagrid.platform.iot.infrastructure.persistence.DeviceRepository;
import com.aquagrid.platform.iot.receiver.application.security.SignatureVerifier;
import com.aquagrid.platform.iot.receiver.domain.model.IdentifierType;
import com.aquagrid.platform.iot.receiver.domain.model.InboundPacket;
import com.aquagrid.platform.iot.receiver.domain.model.PacketCredentials;
import com.aquagrid.platform.iot.receiver.spi.PacketAuthenticator;
import com.aquagrid.platform.iot.web.dto.DeviceDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;

/**
 * Verifies an HMAC signature computed by the device over its own payload.
 *
 * <p>The strongest scheme the receiver supports, and the only one that says anything about the
 * <em>bytes</em>. An API key proves who delivered the packet and a device token proves who holds a
 * secret, but neither notices if the payload was altered in transit. On an unauthenticated bearer —
 * a UDP datagram crossing the public internet — that difference is the whole control, which is why
 * {@code signature-required} exists to make it mandatory per transport.
 *
 * <p>There is an ordering problem worth naming: verifying a signature needs the device's key, and
 * finding the device is normally a later stage. Resolving it here is unavoidable, so this
 * authenticator does a narrow lookup on identifiers the packet already carries and hands the result
 * forward as a proved {@code NETWORK_ADDRESS} binding — meaning the device-resolution stage reuses
 * this work rather than repeating it, and the binding it uses is the one that was cryptographically
 * checked rather than the one that was merely asserted.
 *
 * <p>An unsigned packet from a device that has no key configured is not this authenticator's
 * business: {@link #supports} is false and the chain moves on. Whether that packet is then accepted
 * at all is the trusted-network authenticator's decision, and on a transport listed in
 * {@code signature-required} the answer is no.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HmacSignatureAuthenticator implements PacketAuthenticator {

    public static final int ORDER = 10;

    /** Provisioning key holding the device's shared secret, AES-GCM encrypted at rest. */
    public static final String HMAC_KEY_FIELD = DeviceDto.SECRET_PREFIX + "hmacKey";

    private final DeviceRepository deviceRepository;
    private final CryptoService cryptoService;
    private final SignatureVerifier signatureVerifier;

    @Override
    public String scheme() {
        return "HMAC";
    }

    @Override
    public boolean supports(InboundPacket packet) {
        return packet.credentials().has(PacketCredentials.Keys.SIGNATURE);
    }

    @Override
    @Transactional(readOnly = true)
    public AuthenticationResult authenticate(InboundPacket packet) {
        Optional<Device> candidate = locate(packet);
        if (candidate.isEmpty()) {
            return AuthenticationResult.failure("Signed packet from an unresolvable device");
        }
        Device device = candidate.get();

        Object stored = device.getProvisioning().get(HMAC_KEY_FIELD);
        if (stored == null) {
            return AuthenticationResult.failure(
                    "Device " + device.getDeviceCode() + " presented a signature but has no HMAC key");
        }

        String secretHex;
        try {
            secretHex = cryptoService.decrypt(String.valueOf(stored));
        } catch (RuntimeException e) {
            // A key that will not decrypt is an operational fault — a rotated master key, a
            // restored database — not a bad packet. It must be loud, and it must not be reported to
            // the sender as an authentication failure, which would send a field engineer chasing
            // the device.
            log.error("Cannot decrypt HMAC key for device {} — check the crypto master key",
                    device.getId(), e);
            return AuthenticationResult.failure("Device credential unavailable");
        }

        PacketCredentials credentials = packet.credentials();
        boolean valid = signatureVerifier.verify(
                packet.payload(),
                credentials.get(PacketCredentials.Keys.NONCE),
                credentials.get(PacketCredentials.Keys.TIMESTAMP),
                secretHex,
                credentials.get(PacketCredentials.Keys.SIGNATURE),
                credentials.get(PacketCredentials.Keys.SIGNATURE_ALGORITHM));

        if (!valid) {
            return AuthenticationResult.failure("Signature does not match");
        }
        return AuthenticationResult.success(
                device.getDeviceCode(),
                Map.of(IdentifierType.NETWORK_ADDRESS, device.getNetworkAddress() == null
                        ? device.getDeviceCode() : device.getNetworkAddress()),
                device.getOrganizationId());
    }

    /**
     * Finds the device a signature should be checked against.
     *
     * <p>Only indexed identifiers, and only the ones a signing device would carry. This runs before
     * authentication has succeeded, so it is reachable by anyone who can send a packet — a broad or
     * unindexed search here would be a denial-of-service primitive against the device table.
     */
    private Optional<Device> locate(InboundPacket packet) {
        String address = firstOf(packet,
                IdentifierType.NETWORK_ADDRESS, IdentifierType.DEV_EUI, IdentifierType.IMEI);
        if (address != null) {
            Optional<Device> byAddress = deviceRepository.findFirstByNetworkAddressIgnoreCase(address);
            if (byAddress.isPresent()) {
                return byAddress;
            }
        }
        String serial = packet.identifier(IdentifierType.SERIAL_NUMBER);
        if (serial != null) {
            return deviceRepository.findFirstBySerialNumberIgnoreCase(serial);
        }
        return Optional.empty();
    }

    private static String firstOf(InboundPacket packet, IdentifierType... types) {
        for (IdentifierType type : types) {
            String value = packet.identifier(type);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
