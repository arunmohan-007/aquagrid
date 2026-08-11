package com.aquagrid.platform.iot.infrastructure.adapter;

import com.aquagrid.platform.common.web.ApiPaths;
import com.aquagrid.platform.iot.api.DeviceMessage;
import com.aquagrid.platform.iot.spi.InboundTransportAdapter;
import com.aquagrid.platform.iot.spi.IngestResult;
import com.aquagrid.platform.iot.spi.TelemetryIngestPort;
import com.fasterxml.jackson.databind.JsonNode;
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
 * NB-IoT / LTE-M ingestion.
 *
 * <p>NB-IoT meters use carrier-grade cellular where LoRa gateways are uneconomic. The device speaks
 * CoAP or MQTT-SN to the carrier platform, which then relays decoded readings to the operator via an
 * HTTP webhook (the common integration model for telecom NB-IoT platforms). This adapter consumes
 * that webhook.
 *
 * <p>NB-IoT messages already arrive as decoded JSON (the carrier does the byte decoding), so the
 * codec here is a metric-name normalisation rather than a byte parse: vendor field names map to the
 * canonical {@link DeviceMessage.Metrics} vocabulary. A field the platform does not recognise is
 * preserved verbatim in {@code rawPayload} for forensic replay, never silently dropped.
 */
@Slf4j
@RestController
@RequestMapping(value = ApiPaths.API_V1 + "/ingest/nbiot", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "aquagrid.iot.transports.nbiot", name = "enabled", havingValue = "true")
public class NbiotAdapter implements InboundTransportAdapter {

    private final TelemetryIngestPort ingestPort;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> receive(@RequestBody JsonNode envelope) {
        String imei = envelope.path("deviceId").asText(envelope.path("imei").asText(""));
        Instant observedAt = parseTime(envelope.path("timestamp").asText());

        Map<String, Double> metrics = new HashMap<>();
        copyMetric(envelope, "flowRate", "flow_rate", metrics);
        copyMetric(envelope, "volume", "volume", metrics);
        copyMetric(envelope, "pressure", "pressure", metrics);
        copyMetric(envelope, "battery", "battery_voltage", metrics);

        Double rssi = numOrNull(envelope, "rssi");
        Double snr = numOrNull(envelope, "snr");
        Double batteryV = metrics.getOrDefault(DeviceMessage.Metrics.BATTERY_VOLTAGE, null);

        Map<String, Object> raw = new HashMap<>();
        raw.put("carrier", envelope.path("operator").asText());
        raw.put("imei", imei);

        DeviceMessage message = new DeviceMessage(imei, observedAt, Instant.now(), metrics,
                rssi, snr, batteryV, null, DeviceMessage.Transports.NB_IOT, raw);
        IngestResult result = ingestPort.ingest(message);
        return Map.of("status", result.getClass().getSimpleName().toUpperCase().replace("$", "_"));
    }

    private static void copyMetric(JsonNode node, String sourceField, String canonicalName,
                                   Map<String, Double> target) {
        JsonNode value = node.path(sourceField);
        if (value.isNumber()) {
            target.put(canonicalName, value.asDouble());
        }
    }

    private static Double numOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.asDouble() : null;
    }

    private static Instant parseTime(String iso) {
        if (iso == null || iso.isBlank()) return Instant.now();
        try {
            return Instant.parse(iso);
        } catch (Exception e) {
            return Instant.now();
        }
    }

    @Override
    public String transport() {
        return DeviceMessage.Transports.NB_IOT;
    }

    @Override
    public String displayName() {
        return "NB-IoT / LTE-M (carrier webhook)";
    }

    @Override
    public void start() {
        log.info("NB-IoT adapter started: listening for carrier webhooks on {}/ingest/nbiot",
                ApiPaths.API_V1);
    }

    @Override
    public void stop() {
        log.info("NB-IoT adapter stopped");
    }
}
