package com.aquagrid.platform.iot.receiver.api;

import com.aquagrid.platform.iot.api.DeviceMessage;
import lombok.Builder;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One validated, device-attributed observation, as the receiver hands it on.
 *
 * <p>Immutable, and deliberately richer than {@link DeviceMessage}. The two are not competing
 * models — they sit either side of the ingestion port:
 *
 * <ul>
 *   <li>{@code TelemetryEvent} is the <em>receiver's</em> output. It has been through
 *       authentication, tenant resolution and device resolution, so it can name the tenant, the
 *       device row, the asset the device is fitted to and whether the traffic is real. Alarms,
 *       routing and the packet log all need those, and none of them can be recovered from the wire
 *       payload alone.</li>
 *   <li>{@code DeviceMessage} is the <em>platform's</em> transport-free reading. It predates this
 *       module and is what every downstream consumer already reads.</li>
 * </ul>
 *
 * <p>The event is therefore converted to a {@link DeviceMessage} at the dispatch stage rather than
 * replacing it. Widening the ingestion port's parameter would have forced every existing adapter
 * and every consumer to change for information they do not use, in exchange for nothing — see
 * {@code DispatchStage}.
 *
 * @param eventId              identity of this event, and the packet log row it came from
 * @param correlationId        the trace id the whole reception was logged under
 * @param tenantId             owning organisation, pinned from the resolved device row
 * @param deviceId             the resolved device
 * @param deviceCode           operator-facing device identity, carried so consumers need no join
 * @param assetId              the asset the device is fitted to; null when it is not yet installed
 * @param assetNumber          denormalised asset code — module-iot must not join {@code gis.assets}
 * @param observedAt           when the device took the reading (its clock). The authoritative time
 * @param receivedAt           when the receiver accepted the packet
 * @param communicationProfile the network the packet arrived on, e.g. {@code LORAWAN}
 * @param deviceSource         {@code LIVE} or {@code SIMULATOR}
 * @param latitude             device position at observation, or its registered position
 * @param longitude            device position at observation, or its registered position
 * @param battery              battery voltage, V
 * @param signalStrength       RSSI, dBm. Null for non-radio transports
 * @param signalToNoise        SNR, dB. Null for non-radio transports
 * @param frameCounter         transport frame counter, where the transport has one
 * @param sensorReadings       canonical metric name to value; empty for keep-alives
 * @param alarmFlags           device-asserted condition flags, e.g. {@code TAMPER}
 * @param rawPayload           the decoded envelope, for forensic replay
 * @param metadata             receiver-side context: gateway, topic, port, identifier strategy used
 */
@Builder(toBuilder = true)
public record TelemetryEvent(
        UUID eventId,
        String correlationId,
        UUID tenantId,
        UUID deviceId,
        String deviceCode,
        UUID assetId,
        String assetNumber,
        Instant observedAt,
        Instant receivedAt,
        String communicationProfile,
        String deviceSource,
        Double latitude,
        Double longitude,
        Double battery,
        Double signalStrength,
        Double signalToNoise,
        Integer frameCounter,
        Map<String, Double> sensorReadings,
        Set<String> alarmFlags,
        Map<String, Object> rawPayload,
        Map<String, Object> metadata
) {

    /** Alarm flag names. Device-asserted conditions, distinct from platform-evaluated alarms. */
    public static final class Flags {
        public static final String TAMPER = "TAMPER";
        public static final String REVERSE_FLOW = "REVERSE_FLOW";
        public static final String LEAK = "LEAK";
        public static final String LOW_BATTERY = "LOW_BATTERY";
        public static final String BURST = "BURST";

        private Flags() {
        }
    }

    public TelemetryEvent {
        sensorReadings = sensorReadings == null ? Map.of() : Map.copyOf(sensorReadings);
        alarmFlags = alarmFlags == null ? Set.of() : Set.copyOf(alarmFlags);
        rawPayload = rawPayload == null ? Map.of() : Map.copyOf(rawPayload);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /**
     * Projects this event onto the platform's canonical reading.
     *
     * <p>{@code deviceCode} rather than the network address is <em>not</em> used here: the ingest
     * port resolves through {@code network_address}, so the field it is handed must be the address
     * the device is reached at. Callers set {@code networkAddress} accordingly.
     *
     * <p>Alarm flags round-trip as 0/1 metrics because that is how the existing reading table
     * stores them; the flag set is the receiver's own, structured view of the same bits.
     */
    public DeviceMessage toDeviceMessage(String networkAddress) {
        Map<String, Object> raw = new LinkedHashMap<>(rawPayload);
        raw.put("eventId", String.valueOf(eventId));
        raw.put("correlationId", correlationId);
        return new DeviceMessage(
                networkAddress,
                observedAt,
                receivedAt,
                sensorReadings,
                signalStrength,
                signalToNoise,
                battery,
                frameCounter,
                communicationProfile,
                raw);
    }
}
