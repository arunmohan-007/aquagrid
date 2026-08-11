package com.aquagrid.platform.iot.receiver.application.pipeline;

import com.aquagrid.platform.iot.domain.model.Device;
import com.aquagrid.platform.iot.receiver.api.TelemetryEvent;
import com.aquagrid.platform.iot.receiver.domain.model.ParsedTelemetry;
import com.aquagrid.platform.iot.receiver.domain.model.ReceptionContext;
import com.aquagrid.platform.iot.receiver.spi.ReceiverStage;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Assembles the immutable {@link TelemetryEvent} from everything the pipeline established.
 *
 * <p>The Builder pattern earns its place here rather than being decoration: an event has twenty-one
 * fields drawn from four sources — the packet, the device row, the parsed payload and the pipeline's
 * own notes — and a constructor call with twenty-one positional arguments is a defect waiting for
 * two adjacent {@code Double}s to be transposed, which the compiler cannot catch and no test would
 * notice until a battery voltage appeared as a signal strength.
 *
 * <p>Two substitutions are made here and both are recorded rather than silent:
 *
 * <ul>
 *   <li><b>Time.</b> Where the payload carried no clock, reception time is used and
 *       {@code timestampSource} says so — a consumer reconciling a consumption series needs to know
 *       whether a timestamp describes when water flowed or when a packet arrived.</li>
 *   <li><b>Position.</b> Where the payload carried no position, the device's registered location is
 *       used. Most meters are fixed and have no GPS, so this is the normal case, but a mobile
 *       logger's own fix must win when it has one.</li>
 * </ul>
 */
@Component
public class TelemetryEventStage implements ReceiverStage {

    @Override
    public String name() {
        return "EVENT_BUILD";
    }

    @Override
    public Decision execute(ReceptionContext context) {
        Device device = context.getDevice();
        ParsedTelemetry telemetry = context.getTelemetry();

        boolean deviceClock = telemetry.observedAt() != null;
        Instant observedAt = deviceClock
                ? telemetry.observedAt()
                : context.getPacket().receivedAt();

        // Nulls are dropped, not stored. TelemetryEvent copies this map with Map.copyOf, which
        // throws on a null value — and several of these are legitimately absent (an anonymous
        // packet has no principal, a raw socket has no source of its own).
        Map<String, Object> metadata = new LinkedHashMap<>();
        context.getNotes().forEach((key, value) -> put(metadata, key, value));
        put(metadata, "transport", context.transport());
        put(metadata, "authenticationScheme", context.getAuthenticationScheme());
        put(metadata, "principal", context.getAuthenticatedPrincipal());
        put(metadata, "timestampSource", deviceClock ? "device" : "receiver");
        put(metadata, "sourceIp", context.getPacket().sourceIp());

        Double latitude = telemetry.latitude();
        Double longitude = telemetry.longitude();
        if (latitude == null && longitude == null && device.getLocation() != null) {
            // JTS stores X as longitude and Y as latitude. Getting this the wrong way round puts
            // Kerala in Somalia, and the coordinates are plausible enough that nothing complains.
            longitude = device.getLocation().getX();
            latitude = device.getLocation().getY();
            put(metadata, "positionSource", "registration");
        } else if (latitude != null || longitude != null) {
            put(metadata, "positionSource", "device");
        }

        TelemetryEvent event = TelemetryEvent.builder()
                .eventId(context.packetId())
                .correlationId(context.correlationId())
                .tenantId(device.getOrganizationId())
                .deviceId(device.getId())
                .deviceCode(device.getDeviceCode())
                .assetId(device.getAssetId())
                .assetNumber(device.getAssetNumber())
                .observedAt(observedAt)
                .receivedAt(context.getPacket().receivedAt())
                .communicationProfile(context.getProfile().name())
                .deviceSource(device.getSource())
                .latitude(latitude)
                .longitude(longitude)
                .battery(telemetry.battery())
                .signalStrength(telemetry.signalStrength())
                .signalToNoise(telemetry.signalToNoise())
                .frameCounter(telemetry.frameCounter())
                .sensorReadings(telemetry.metrics())
                .alarmFlags(telemetry.alarmFlags())
                .rawPayload(telemetry.raw())
                .metadata(metadata)
                .build();

        context.built(event);
        return Decision.CONTINUE;
    }

    private static void put(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    @Override
    public int getOrder() {
        return Stages.EVENT_BUILD;
    }
}
