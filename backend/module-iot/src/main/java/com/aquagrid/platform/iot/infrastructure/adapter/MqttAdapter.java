package com.aquagrid.platform.iot.infrastructure.adapter;

import com.aquagrid.platform.iot.api.DeviceMessage;
import com.aquagrid.platform.iot.infrastructure.config.IotProperties;
import com.aquagrid.platform.iot.spi.InboundTransportAdapter;
import com.aquagrid.platform.iot.spi.TelemetryIngestPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * MQTT ingestion.
 *
 * <p>4G/Cellular loggers, pump-station gateways and SCADA bridges publish to MQTT (QoS 1 for
 * durability, retained state for liveness). ChirpStack also publishes LoRaWAN uplinks to MQTT, so
 * this adapter is the single ingestion path for deployments that prefer a broker over per-transport
 * webhooks.
 *
 * <p>Phase 5 establishes the adapter lifecycle and the message-decoding contract. The production
 * subscriber uses {@code MqttPahoMessageDrivenChannelAdapter} (Spring Integration MQTT); that
 * dependency and the live broker connection land with Module 18. The {@code onMessage} hook below is
 * the seam: it takes a topic payload, decodes it into a {@link DeviceMessage} exactly as the HTTP
 * and NB-IoT adapters do, and hands it to the same {@link TelemetryIngestPort}. Swapping the
 * subscriber from this stub to Paho changes one class, not the codec or the ingest path.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "aquagrid.iot.transports.mqtt", name = "enabled", havingValue = "true")
public class MqttAdapter implements InboundTransportAdapter {

    private final TelemetryIngestPort ingestPort;
    private final ObjectMapper objectMapper;
    private final IotProperties properties;

    /**
     * The topic filter every uplink must match. Defaulted to the ChirpStack convention; configurable
     * because SCADA bridges use their own topic trees.
     */
    private static final String DEFAULT_TOPIC_FILTER = "application/+/device/+/event/up";

    @PostConstruct
    @Override
    public void start() {
        String broker = properties.transports().mqtt().endpoint();
        log.info("MQTT adapter started: broker={}, topic={} (subscriber activation in Module 18)",
                broker, DEFAULT_TOPIC_FILTER);
        // The live MqttPahoMessageDrivenChannelAdapter is constructed here in Module 18.
        // onMessage(...) is its message handler, unchanged from the prototype below.
    }

    @PreDestroy
    @Override
    public void stop() {
        log.info("MQTT adapter stopped");
    }

    @Override
    public String transport() {
        return DeviceMessage.Transports.MQTT;
    }

    @Override
    public String displayName() {
        return "MQTT (broker subscriber)";
    }

    /**
     * The handler the broker invokes per uplink. Decoded exactly like the HTTP/NB-IoT paths, then
     * handed to the transport-agnostic port. Public so the Module 18 subscriber can call it directly.
     */
    public void onMessage(String topic, byte[] payload) {
        try {
            JsonNode envelope = objectMapper.readTree(payload);
            String deviceEui = extractDeviceEui(topic, envelope);
            Map<String, Double> metrics = new HashMap<>();
            JsonNode data = envelope.path("data");
            if (data.isObject()) {
                data.fields().forEachRemaining(entry -> {
                    if (entry.getValue().isNumber()) {
                        metrics.put(canonicalise(entry.getKey()), entry.getValue().asDouble());
                    }
                });
            }
            Double batteryV = metrics.get(DeviceMessage.Metrics.BATTERY_VOLTAGE);
            DeviceMessage message = new DeviceMessage(deviceEui,
                    parseTime(envelope.path("time").asText()), Instant.now(), metrics,
                    numOrNull(envelope, "rssi"), numOrNull(envelope, "snr"), batteryV,
                    envelope.path("fCnt").canConvertToInt() ? envelope.path("fCnt").asInt() : null,
                    DeviceMessage.Transports.MQTT, Map.of("topic", topic));
            ingestPort.ingest(message);
        } catch (Exception e) {
            log.warn("Failed to decode MQTT message on {}: {}", topic, e.getMessage());
        }
    }

    /** ChirpStack topic convention: application/{app}/device/{devEui}/event/up */
    private static String extractDeviceEui(String topic, JsonNode envelope) {
        String[] parts = topic.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if ("device".equals(parts[i]) && i + 1 < parts.length) {
                return parts[i + 1];
            }
        }
        return envelope.path("deviceInfo").path("devEui").asText();
    }

    private static String canonicalise(String vendorField) {
        // Normalises the common vendor field names. Anything unmatched is passed through; the
        // analytics layer treats unknown metrics as opaque.
        return switch (vendorField.toLowerCase()) {
            case "flowrate", "flow_rate" -> DeviceMessage.Metrics.FLOW_RATE;
            case "volume", "totalvolume" -> DeviceMessage.Metrics.VOLUME;
            case "pressure" -> DeviceMessage.Metrics.PRESSURE;
            case "battery", "batteryvoltage", "battery_voltage" -> DeviceMessage.Metrics.BATTERY_VOLTAGE;
            case "temperature" -> DeviceMessage.Metrics.TEMPERATURE;
            case "tamper" -> DeviceMessage.Metrics.TAMPER;
            default -> vendorField;
        };
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
}
