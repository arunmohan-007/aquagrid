package com.aquagrid.platform.iot.api;

import java.time.Instant;
import java.util.Map;

/**
 * The canonical, protocol-free representation of one device uplink.
 *
 * <p>This is the <b>only</b> shape the business logic above the ingestion port ever sees. Whether a
 * reading arrived over LoRaWAN, NB-IoT, MQTT, HTTP or a CSV upload is invisible above this record —
 * which is exactly what makes the platform transport-agnostic, the brief's hard requirement.
 *
 * <p>Adapters ({@link com.aquagrid.platform.iot.spi.InboundTransportAdapter}) are responsible for
 * translating their vendor-specific bytes into this model. Adding LTE-M in 2027 = adding one class;
 * zero changes to metering, billing, alarms, NRW or GIS.
 *
 * <p>Fields are grouped: identity, timing, the metrics map, and radio-quality context. The metrics
 * map is keyed by canonical metric names ({@code flow_rate}, {@code pressure}, {@code volume},
 * {@code battery_voltage}, {@code tamper}, {@code reverse_flow}) so downstream code reads stable
 * keys regardless of how a vendor encoded them.
 *
 * @param deviceEui   globally identifies the device within the tenant
 * @param observedAt  when the reading was taken on the device (its clock). The authoritative time.
 * @param receivedAt  when the platform received it. Differs from observedAt by transport latency.
 * @param metrics     canonical metric name → value. Empty for keep-alive / status uplinks.
 * @param rssi        received signal strength, dBm. Null for non-radio transports.
 * @param snr         signal-to-noise ratio, dB. Null for non-radio transports.
 * @param batteryV    battery voltage at the device. Null for mains-powered devices.
 * @param fCnt        frame counter (LoRaWAN). Null for transports without one.
 * @param transport   which transport delivered this message. For observability and quality scoring.
 * @param rawPayload  the original decoded payload, for forensic replay. Never raw cipher bytes.
 */
public record DeviceMessage(
        String deviceEui,
        Instant observedAt,
        Instant receivedAt,
        Map<String, Double> metrics,
        Double rssi,
        Double snr,
        Double batteryV,
        Integer fCnt,
        String transport,
        Map<String, Object> rawPayload
) {
    public DeviceMessage {
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
        rawPayload = rawPayload == null ? Map.of() : Map.copyOf(rawPayload);
    }

    /** Canonical metric names. Adapters normalise vendor names to these. */
    public static final class Metrics {
        public static final String FLOW_RATE = "flow_rate";          // L/min
        public static final String VOLUME = "volume";                // L (cumulative)
        public static final String PRESSURE = "pressure";            // bar
        public static final String BATTERY_VOLTAGE = "battery_voltage"; // V
        public static final String TAMPER = "tamper";                // 0/1
        public static final String REVERSE_FLOW = "reverse_flow";    // 0/1
        public static final String TEMPERATURE = "temperature";      // °C
        public static final String LEAK = "leak";                    // 0/1 (device-side flag)
        private Metrics() {
        }
    }

    /** Transport identifiers, surfaced verbatim in the {@code transport} field. */
    public static final class Transports {
        public static final String LORAWAN = "LORAWAN";
        public static final String NB_IOT = "NB_IOT";
        public static final String CELLULAR = "CELLULAR";
        public static final String MQTT = "MQTT";
        public static final String HTTP = "HTTP";
        public static final String TCP = "TCP";
        public static final String UDP = "UDP";
        public static final String WEBSOCKET = "WEBSOCKET";
        /**
         * Retained for the readings written before V1402 split {@link
         * com.aquagrid.platform.iot.domain.model.DeviceSource} out of the transport axis. Nothing
         * emits it now: a simulated device declares the network it emulates and is tagged
         * {@code SIMULATOR} on its device row instead.
         */
        public static final String SIMULATOR = "SIMULATOR";
        private Transports() {
        }
    }
}
