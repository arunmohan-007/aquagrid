package com.aquagrid.platform.iot.receiver.application.resolver;

import com.aquagrid.platform.iot.domain.model.Device;
import com.aquagrid.platform.iot.receiver.domain.model.IdentifierType;
import com.aquagrid.platform.iot.receiver.domain.model.ReceptionContext;
import com.aquagrid.platform.iot.receiver.spi.DeviceResolutionStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Finds the registered device a packet belongs to.
 *
 * <p>Runs the {@link DeviceResolutionStrategy} beans in order and takes the first match. Two things
 * make this cheap enough to sit on the ingestion path:
 *
 * <ul>
 *   <li><b>Strategies are skipped when the packet carries none of their identifiers.</b> A fleet of
 *       DevEUI-addressed meters never pays for the MQTT topic strategy, because a LoRaWAN uplink
 *       has no topic — so the common case is one indexed lookup, not six.</li>
 *   <li><b>Order encodes trust and cost together.</b> Proved bindings first, then indexed exact
 *       matches, then the weaker inferences. The expensive, speculative work only ever runs for
 *       packets nothing better could place.</li>
 * </ul>
 *
 * <p>Adding a way to identify a device — a Modbus unit id, an OPC-UA node — is one new bean.
 * Nothing here changes, and no other stage learns that identifier kinds exist.
 */
@Slf4j
@Service
public class DeviceResolver {

    private final List<DeviceResolutionStrategy> strategies;

    public DeviceResolver(List<DeviceResolutionStrategy> strategies) {
        List<DeviceResolutionStrategy> ordered = new ArrayList<>(strategies);
        ordered.sort(AnnotationAwareOrderComparator.INSTANCE);
        this.strategies = List.copyOf(ordered);
        log.info("Device resolution chain: {}",
                this.strategies.stream().map(DeviceResolutionStrategy::name).toList());
    }

    /**
     * @return the device, or empty when no strategy could place the packet — which is what the
     *         caller turns into {@code RECEIVER_UNKNOWN_DEVICE}. The name of the strategy that
     *         succeeded is recorded on the context, and from there on the packet log, so it is
     *         visible <em>how</em> a device was identified rather than only that it was
     */
    public Optional<Device> resolve(ReceptionContext context) {
        for (DeviceResolutionStrategy strategy : strategies) {
            if (!hasAnyIdentifier(context, strategy)) {
                continue;
            }
            Optional<Device> device = strategy.resolve(context);
            if (device.isPresent()) {
                context.note("resolutionStrategy", strategy.name());
                return device;
            }
        }
        return Optional.empty();
    }

    /** Which strategy placed the device, for the packet log. Null when none did. */
    public static String strategyOf(ReceptionContext context) {
        Object value = context.getNotes().get("resolutionStrategy");
        return value == null ? null : String.valueOf(value);
    }

    private static boolean hasAnyIdentifier(ReceptionContext context,
                                            DeviceResolutionStrategy strategy) {
        for (IdentifierType type : strategy.supportedIdentifiers()) {
            String value = context.identifier(type);
            if (value != null && !value.isBlank()) {
                return true;
            }
        }
        return false;
    }
}
