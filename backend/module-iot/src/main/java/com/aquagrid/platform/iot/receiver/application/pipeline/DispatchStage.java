package com.aquagrid.platform.iot.receiver.application.pipeline;

import com.aquagrid.platform.common.error.ErrorCode;
import com.aquagrid.platform.iot.api.DeviceMessage;
import com.aquagrid.platform.iot.domain.model.Device;
import com.aquagrid.platform.iot.receiver.api.TelemetryEvent;
import com.aquagrid.platform.iot.receiver.domain.model.ReceptionContext;
import com.aquagrid.platform.iot.receiver.spi.ReceiverStage;
import com.aquagrid.platform.iot.spi.IngestResult;
import com.aquagrid.platform.iot.spi.TelemetryIngestPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Hands the event to {@link TelemetryIngestPort}, where it becomes durable state.
 *
 * <p>The event is projected onto a {@link DeviceMessage} rather than the port being widened to
 * accept a {@code TelemetryEvent}. That is a boundary decision worth stating plainly, because the
 * alternative looks tidier:
 *
 * <ul>
 *   <li>{@code DeviceMessage} is the platform's published, transport-free reading and predates this
 *       module. Metering, billing, alarms, NRW and GIS all read it. Changing its shape would make
 *       every one of them depend on receiver concepts — packet ids, authentication schemes, source
 *       addresses — that none of them use.</li>
 *   <li>The ingestion port is the seam along which the IoT tier is meant to be extracted into a
 *       service. A narrow, stable parameter is what makes that extraction a change of transport
 *       rather than a redesign.</li>
 *   <li>The richer detail is not lost: it is on the packet log, keyed by the same id the event
 *       carries, which is where a forensic question is asked from anyway.</li>
 * </ul>
 *
 * <p>The message is addressed by {@code networkAddress}, since that is the column the ingest
 * service resolves through — with the device code as the fallback for a profile that nominates no
 * network identity. Sending anything else would have the port fail to resolve a device the receiver
 * has already resolved, which is the most confusing failure this pipeline could produce.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DispatchStage implements ReceiverStage {

    private final TelemetryIngestPort ingestPort;

    @Override
    public String name() {
        return "DISPATCH";
    }

    @Override
    public Decision execute(ReceptionContext context) {
        Device device = context.getDevice();
        TelemetryEvent event = context.getEvent();

        String address = device.getNetworkAddress() != null && !device.getNetworkAddress().isBlank()
                ? device.getNetworkAddress()
                : device.getDeviceCode();

        IngestResult result = ingestPort.ingest(event.toDeviceMessage(address));

        return switch (result) {
            case IngestResult.Accepted accepted -> Decision.CONTINUE;
            case IngestResult.Duplicate duplicate -> {
                // The receiver's own replay check already passed, so reaching here means the ingest
                // service's independent dedup caught something the receiver's key did not — a
                // legitimate outcome given they key differently, and still a duplicate.
                context.markDuplicate();
                yield Decision.HALT;
            }
            case IngestResult.Rejected rejected -> {
                // The receiver resolved this device and validated this payload, so a refusal here
                // is a disagreement between two layers rather than a bad packet. Logged at warn
                // because it means one of them is wrong.
                log.warn("Ingest port rejected packet {} for device {}: {}",
                        context.packetId(), device.getId(), rejected.reason());
                context.reject(ErrorCode.RECEIVER_VALIDATION_FAILED, rejected.reason());
                yield Decision.HALT;
            }
        };
    }

    @Override
    public int getOrder() {
        return Stages.DISPATCH;
    }
}
