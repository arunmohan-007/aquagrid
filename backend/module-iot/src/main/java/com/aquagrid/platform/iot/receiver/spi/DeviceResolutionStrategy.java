package com.aquagrid.platform.iot.receiver.spi;

import com.aquagrid.platform.iot.domain.model.Device;
import com.aquagrid.platform.iot.receiver.domain.model.IdentifierType;
import com.aquagrid.platform.iot.receiver.domain.model.ReceptionContext;
import org.springframework.core.Ordered;

import java.util.Optional;
import java.util.Set;

/**
 * One way of getting from a packet's claims to a registered device.
 *
 * <p>Pluggable because there is no single answer. LoRaWAN addresses a device by DevEUI, NB-IoT by
 * IMEI, MQTT by client id or by the topic it published on, an HTTP integration by a token it was
 * issued. A resolver that hard-coded any of those would need editing for the next technology; a set
 * of strategies indexed by the identifiers they consume does not.
 *
 * <p>Strategies run in {@link Ordered} order and the first match wins, so order encodes trust and
 * cost together. Cryptographically proved bindings first (a device token identifies exactly one
 * device and was verified), then indexed exact lookups, then anything requiring a scan of the
 * {@code provisioning} JSONB — which is both the weakest evidence and the most expensive query, so
 * it should only ever run for packets nothing better could place.
 *
 * <p>Implementations must be thread-safe and side-effect free: several run per packet, and a packet
 * that no strategy can place must leave no trace but a rejection.
 */
public interface DeviceResolutionStrategy extends Ordered {

    /** Name recorded on the packet log, so it is visible <em>how</em> a device was identified. */
    String name();

    /**
     * The identifier kinds this strategy can act on. The resolver skips a strategy when the packet
     * carries none of them, which is what keeps a fleet of DevEUI-addressed meters from paying for
     * the MQTT topic strategy on every uplink.
     */
    Set<IdentifierType> supportedIdentifiers();

    /**
     * Attempts to place the packet.
     *
     * @return the device, or empty when this strategy cannot place it. Empty means "not mine",
     *         never "unknown device" — only the resolver, having exhausted every strategy, may
     *         conclude that
     */
    Optional<Device> resolve(ReceptionContext context);
}
