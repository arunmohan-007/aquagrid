package com.aquagrid.platform.iot.receiver.application.resolver;

import com.aquagrid.platform.iot.domain.model.Device;
import com.aquagrid.platform.iot.infrastructure.persistence.DeviceRepository;
import com.aquagrid.platform.iot.receiver.domain.model.IdentifierType;
import com.aquagrid.platform.iot.receiver.domain.model.ReceptionContext;
import com.aquagrid.platform.iot.receiver.spi.DeviceResolutionStrategy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves a device by the address its own technology reaches it on.
 *
 * <p>The main path, and the one almost every packet takes. {@code Device.networkAddress} holds
 * whichever identifier the device's {@link com.aquagrid.platform.iot.domain.model.CommunicationProfile}
 * nominates — DevEUI for LoRaWAN, IMEI for NB-IoT and cellular, client id for MQTT, unit id for the
 * socket transports — normalised to upper case at registration. That normalisation is why one
 * indexed lookup serves every technology and why the receiver needs no switch on transport to
 * resolve a device.
 *
 * <p>The identifier kinds below are tried in order, but they are alternatives rather than a
 * hierarchy: a packet carries at most one of them, because a device speaks one technology. The
 * order only decides which is consulted first when a transport helpfully reports the same value
 * under two names.
 *
 * <p>Resolution is deliberately cross-tenant. Ingestion is not authenticated as a tenant — a
 * gateway delivers bytes carrying only a radio identifier — so the tenant is pinned from the
 * resolved row. Accepting a tenant from the wire would let one municipality's gateway file readings
 * against another's meters.
 */
@Component
@RequiredArgsConstructor
public class NetworkAddressStrategy implements DeviceResolutionStrategy {

    public static final int ORDER = 20;

    private static final List<IdentifierType> CANDIDATES = List.of(
            IdentifierType.NETWORK_ADDRESS,
            IdentifierType.DEV_EUI,
            IdentifierType.IMEI);

    private final DeviceRepository deviceRepository;

    @Override
    public String name() {
        return "NETWORK_ADDRESS";
    }

    @Override
    public Set<IdentifierType> supportedIdentifiers() {
        return Set.copyOf(CANDIDATES);
    }

    @Override
    public Optional<Device> resolve(ReceptionContext context) {
        for (IdentifierType type : CANDIDATES) {
            String value = context.identifier(type);
            if (value == null || value.isBlank()) {
                continue;
            }
            Optional<Device> device = deviceRepository.findFirstByNetworkAddressIgnoreCase(value.trim());
            if (device.isPresent()) {
                return device;
            }
        }
        return Optional.empty();
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
