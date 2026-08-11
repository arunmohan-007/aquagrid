package com.aquagrid.platform.iot.dataconfig.application.receiver;

import com.aquagrid.platform.iot.dataconfig.application.service.RawTelemetryService;
import com.aquagrid.platform.iot.domain.model.Device;
import com.aquagrid.platform.iot.receiver.domain.model.ParsedTelemetry;
import com.aquagrid.platform.iot.receiver.domain.model.ReceptionContext;
import com.aquagrid.platform.iot.receiver.domain.model.ReceptionStatus;
import com.aquagrid.platform.iot.receiver.spi.ReceptionObserver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Preserves the complete payload of every packet the receiver takes delivery of.
 *
 * <p>Small, because it is only an adapter: everything it knows how to do is in
 * {@link RawTelemetryService}, and everything it is allowed to see is on the {@link ReceptionContext}
 * the pipeline left behind. What earns it a class of its own is <em>where</em> it runs.
 *
 * <p>An observer, not a stage. A stage runs only if every stage before it let the packet through,
 * which would mean the payloads never stored are the ones from unregistered devices, failed
 * authentications and unparseable bodies — precisely the packets whose contents somebody needs to
 * read. Running after the chain, in the {@code finally} block that already guarantees every packet
 * is accounted for, is what makes "accept and permanently preserve everything" true of rejected
 * traffic as well as accepted traffic.
 *
 * <p>It also runs for the simulator, without a line of simulator-specific code, because the
 * simulator emits through {@code ReceiverGateway} exactly as a physical device does.
 */
@Component
@RequiredArgsConstructor
public class RawPayloadRetentionObserver implements ReceptionObserver {

    private final RawTelemetryService rawTelemetryService;

    @Override
    public void onReception(ReceptionContext context, ReceptionStatus status) {
        Device device = context.getDevice();
        ParsedTelemetry telemetry = context.getTelemetry();

        rawTelemetryService.store(new RawTelemetryService.StoreCommand(
                context.packetId(),
                device == null ? null : device.getOrganizationId(),
                device == null ? null : device.getId(),
                device == null ? null : device.getDeviceCode(),
                device == null ? null : device.getAssetId(),
                device == null ? null : device.getAssetNumber(),
                // The device's own clock, where a parser found one. Null is a fact worth keeping —
                // it says the reading's timestamp is the server's, not the device's.
                telemetry == null ? null : telemetry.observedAt(),
                context.getPacket().receivedAt(),
                // The device's registered network, and the bearer it actually arrived on. Routinely
                // different: a ChirpStack uplink is LORAWAN over HTTP.
                context.getProfile() == null ? null : context.getProfile().name(),
                context.transport(),
                context.correlationId(),
                context.getPacket().sourceIp(),
                context.getPacket().payload(),
                status.name(),
                context.isRejected() ? context.getRejection().detail() : null));
    }
}
