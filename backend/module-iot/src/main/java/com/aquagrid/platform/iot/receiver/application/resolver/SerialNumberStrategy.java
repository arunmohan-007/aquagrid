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

/**
 * Resolves a device by its serial number or its operator-facing device code.
 *
 * <p>Last in the chain, because these are the identifiers a <em>human</em> uses. A serial number is
 * what is printed on the meter and read out over the phone; a device code is what appears on the
 * work order. Devices do sometimes report them — a firmware that sends what it can read off its own
 * label, a bench-test harness — and refusing to resolve on them would strand exactly the devices
 * whose commissioning is being verified.
 *
 * <p>They sort last because neither is a network identity and neither is unique across tenants:
 * {@code device_code} is unique per tenant only, and serial numbers repeat across manufacturers.
 * Both queries take the first match, so if any earlier strategy can place the packet it should.
 */
@Component
@RequiredArgsConstructor
public class SerialNumberStrategy implements DeviceResolutionStrategy {

    public static final int ORDER = 60;

    private final DeviceRepository deviceRepository;

    @Override
    public String name() {
        return "SERIAL_OR_CODE";
    }

    @Override
    public Set<IdentifierType> supportedIdentifiers() {
        return Set.of(IdentifierType.SERIAL_NUMBER, IdentifierType.DEVICE_CODE);
    }

    @Override
    public Optional<Device> resolve(ReceptionContext context) {
        String serial = context.identifier(IdentifierType.SERIAL_NUMBER);
        if (serial != null && !serial.isBlank()) {
            Optional<Device> bySerial = deviceRepository.findFirstBySerialNumberIgnoreCase(serial.trim());
            if (bySerial.isPresent()) {
                return bySerial;
            }
        }
        String code = context.identifier(IdentifierType.DEVICE_CODE);
        if (code != null && !code.isBlank()) {
            return deviceRepository.findFirstByDeviceCodeIgnoreCase(code.trim());
        }
        return Optional.empty();
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
