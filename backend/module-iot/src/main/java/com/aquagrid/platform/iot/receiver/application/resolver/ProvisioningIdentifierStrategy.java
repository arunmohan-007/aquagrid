package com.aquagrid.platform.iot.receiver.application.resolver;

import com.aquagrid.platform.iot.domain.model.Device;
import com.aquagrid.platform.iot.infrastructure.persistence.DeviceRepository;
import com.aquagrid.platform.iot.receiver.domain.model.IdentifierType;
import com.aquagrid.platform.iot.receiver.domain.model.ReceptionContext;
import com.aquagrid.platform.iot.receiver.spi.DeviceResolutionStrategy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves a device by a secondary identifier held in its {@code provisioning} block.
 *
 * <p>One strategy for several identifier kinds rather than one class each, because the difference
 * between them is entirely data: which identifier maps to which provisioning key, and whether the
 * comparison is case-sensitive. Splitting that into four near-identical classes would be four
 * copies of the same query with a constant changed — and the point of the mapping table below is
 * that adding a fifth identifier is a line, not a file.
 *
 * <p>This runs only when {@link NetworkAddressStrategy} could not place the packet, which is the
 * situation these exist for: a device whose profile nominates one identity field but whose traffic
 * arrives bearing another. A TCP logger framing a MAC address, an MQTT bridge whose client id is
 * the meter rather than the bridge.
 *
 * <p><b>Every key here has an expression index (V1403), and that is a hard constraint.</b> A key
 * without one turns this into a sequential scan of the device table, executed on the ingestion path
 * for every packet no other strategy could place — which is to say, precisely on the traffic a
 * misconfigured fleet generates most of.
 */
@Component
@RequiredArgsConstructor
public class ProvisioningIdentifierStrategy implements DeviceResolutionStrategy {

    public static final int ORDER = 40;

    /**
     * Identifier kind → the provisioning key holding it, and whether to compare case-insensitively.
     *
     * <p>Ordered: a packet may carry more than one of these and the first is the more specific.
     * Case folding follows how the value is written at registration — hardware addresses and unit
     * ids are hex and arrive in both cases, whereas an MQTT client id is an opaque string the
     * broker treats as case-sensitive, so folding it would merge two legitimately distinct clients.
     */
    private static final Map<IdentifierType, Key> KEYS = new LinkedHashMap<>(Map.of());

    static {
        KEYS.put(IdentifierType.MQTT_CLIENT_ID, new Key("clientId", false));
        KEYS.put(IdentifierType.MAC_ADDRESS, new Key("macAddress", true));
        KEYS.put(IdentifierType.CUSTOM, new Key("unitId", true));
    }

    private record Key(String provisioningField, boolean ignoreCase) {
    }

    private final DeviceRepository deviceRepository;

    @Override
    public String name() {
        return "PROVISIONING_IDENTIFIER";
    }

    @Override
    public Set<IdentifierType> supportedIdentifiers() {
        return KEYS.keySet();
    }

    @Override
    public Optional<Device> resolve(ReceptionContext context) {
        for (Map.Entry<IdentifierType, Key> entry : KEYS.entrySet()) {
            String value = context.identifier(entry.getKey());
            if (value == null || value.isBlank()) {
                continue;
            }
            Key key = entry.getValue();
            Optional<Device> device = key.ignoreCase()
                    ? deviceRepository.findFirstByProvisioningFieldIgnoreCase(
                            key.provisioningField(), value.trim())
                    : deviceRepository.findFirstByProvisioningField(
                            key.provisioningField(), value.trim());
            if (device.isPresent()) {
                context.note("resolvedBy", entry.getKey().name());
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
