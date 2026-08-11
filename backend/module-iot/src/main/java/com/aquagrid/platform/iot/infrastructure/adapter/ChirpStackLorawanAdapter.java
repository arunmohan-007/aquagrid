package com.aquagrid.platform.iot.infrastructure.adapter;

import com.aquagrid.platform.common.web.ApiPaths;
import com.aquagrid.platform.iot.api.DeviceMessage;
import com.aquagrid.platform.iot.spi.InboundTransportAdapter;
import com.aquagrid.platform.iot.spi.IngestResult;
import com.aquagrid.platform.iot.spi.TelemetryIngestPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * LoRaWAN ingestion via the ChirpStack network server.
 *
 * <p>ChirpStack is the open-source, self-hostable LoRaWAN NS (mandatory for on-prem/air-gapped
 * government deployments). It publishes uplinks to MQTT by default, but also exposes an HTTP
 * integration webhook. Phase 5 implements the HTTP integration: simpler to operate behind nginx,
 * no broker dependency, and the codec logic is identical for both delivery paths.
 *
 * <p>The adapter is responsible for two things: receiving the ChirpStack envelope, and decoding the
 * device's LoRaWAN payload bytes into canonical metrics. The decoding here covers the common smart-
 * water-meter payload: a cumulative volume (4 bytes, little-endian, decilitres), a flow rate, and a
 * status byte whose bits encode tamper / reverse-flow / leak flags.
 *
 * <p>Adding a vendor whose payload differs = adding a codec class. The transport, the ingest port
 * and every downstream consumer stay unchanged. This is the seam the brief calls out as critical.
 */
@Slf4j
@RestController
@RequestMapping(value = ApiPaths.API_V1 + "/ingest/lorawan", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "aquagrid.iot.transports.lorawan", name = "enabled", havingValue = "true")
public class ChirpStackLorawanAdapter implements InboundTransportAdapter {

    private final TelemetryIngestPort ingestPort;
    private final ObjectMapper objectMapper;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> receive(@RequestBody JsonNode envelope) {
        String deviceEui = envelope.path("deviceInfo").path("devEui").asText();
        String payloadHex = envelope.path("data").asText("");
        int fCnt = envelope.path("fCnt").asInt(0);
        Instant observedAt = Instant.now();
        String time = envelope.path("time").asText();
        if (!time.isBlank()) {
            try {
                observedAt = Instant.parse(time);
            } catch (Exception ignored) {
                // fall back to server time; device time is preferred but not mandatory
            }
        }

        DeviceMessage message = decode(deviceEui, payloadHex, fCnt, observedAt,
                numOrNull(envelope, "dr"),
                numOrNull(envelope, "rssi"), numOrNull(envelope, "snr"));
        IngestResult result = ingestPort.ingest(message);
        return Map.of("status", result.getClass().getSimpleName().toUpperCase().replace("$", "_"));
    }

    /**
     * Decodes a hex payload into canonical metrics.
     *
     * <p>Layout (typical smart water meter):
     * <pre>
     *   bytes 0-3 : cumulative volume, uint32 LE, decilitres
     *   bytes 4-5 : flow rate, uint16 LE, centilitres/min
     *   byte  6   : battery voltage, decivolts
     *   byte  7   : status flags (bit0 tamper, bit1 reverse flow, bit2 leak)
     * </pre>
     * Unknown/short payloads still ingest (keep-alive), with an empty metrics map.
     */
    static DeviceMessage decode(String deviceEui, String payloadHex, int fCnt, Instant observedAt,
                                Double dr, Double rssi, Double snr) {
        Map<String, Double> metrics = new HashMap<>();
        Map<String, Object> raw = new HashMap<>();
        raw.put("payloadHex", payloadHex);
        raw.put("dr", dr);

        byte[] bytes = hexToBytes(payloadHex);
        if (bytes.length >= 4) {
            long volumeDl = ((bytes[3] & 0xFFL) << 24) | ((bytes[2] & 0xFFL) << 16)
                    | ((bytes[1] & 0xFFL) << 8) | (bytes[0] & 0xFFL);
            metrics.put(DeviceMessage.Metrics.VOLUME, volumeDl / 10.0); // dL → L
        }
        if (bytes.length >= 6) {
            int flowCl = ((bytes[5] & 0xFF) << 8) | (bytes[4] & 0xFF);
            metrics.put(DeviceMessage.Metrics.FLOW_RATE, flowCl / 100.0); // cL/min → L/min
        }
        if (bytes.length >= 7) {
            metrics.put(DeviceMessage.Metrics.BATTERY_VOLTAGE, (bytes[6] & 0xFF) / 10.0); // dV → V
        }
        if (bytes.length >= 8) {
            int flags = bytes[7] & 0xFF;
            metrics.put(DeviceMessage.Metrics.TAMPER, (flags & 0x01) != 0 ? 1.0 : 0.0);
            metrics.put(DeviceMessage.Metrics.REVERSE_FLOW, (flags & 0x02) != 0 ? 1.0 : 0.0);
            metrics.put(DeviceMessage.Metrics.LEAK, (flags & 0x04) != 0 ? 1.0 : 0.0);
        }

        Double batteryV = metrics.containsKey(DeviceMessage.Metrics.BATTERY_VOLTAGE)
                ? metrics.get(DeviceMessage.Metrics.BATTERY_VOLTAGE) : null;
        return new DeviceMessage(deviceEui, observedAt, Instant.now(), metrics,
                rssi, snr, batteryV, fCnt, DeviceMessage.Transports.LORAWAN, raw);
    }

    private static Double numOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.asDouble() : null;
    }

    private static byte[] hexToBytes(String hex) {
        if (hex == null || hex.length() % 2 != 0) {
            return new byte[0];
        }
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    @Override
    public String transport() {
        return DeviceMessage.Transports.LORAWAN;
    }

    @Override
    public String displayName() {
        return "LoRaWAN (ChirpStack HTTP integration)";
    }

    @Override
    public void start() {
        log.info("LoRaWAN adapter started: listening for ChirpStack HTTP integrations on {}/ingest/lorawan",
                ApiPaths.API_V1);
    }

    @Override
    public void stop() {
        log.info("LoRaWAN adapter stopped");
    }
}
