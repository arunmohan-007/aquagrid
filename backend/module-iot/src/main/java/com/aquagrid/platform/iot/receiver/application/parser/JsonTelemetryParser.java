package com.aquagrid.platform.iot.receiver.application.parser;

import com.aquagrid.platform.iot.api.DeviceMessage;
import com.aquagrid.platform.iot.receiver.domain.model.ParsedTelemetry;
import com.aquagrid.platform.iot.receiver.domain.model.ReceptionContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Decodes structured JSON telemetry.
 *
 * <p>The general case, and the last parser tried: HTTP integrations, NB-IoT carrier webhooks, MQTT
 * bridges and WebSocket clients all send some JSON object with readings in it. What varies is the
 * spelling, which {@link MetricVocabulary} absorbs, and the nesting, which this handles by looking
 * in the places vendors actually put things — the top level, {@code data}, {@code object},
 * {@code measurements}, {@code payload}.
 *
 * <p>Deliberately permissive about structure and strict about nothing. A payload the receiver
 * cannot find readings in still decodes as a keep-alive rather than a rejection, because a device
 * that reported in with an unrecognised body is still a device that is alive — and the raw payload
 * is on the packet log for whoever has to work out what it meant.
 */
@Component
@RequiredArgsConstructor
public class JsonTelemetryParser extends AbstractPayloadParser {

    public static final int ORDER = 50;

    /** Where readings hide, in the order vendors nest them. */
    private static final String[] READING_CONTAINERS = {"data", "object", "measurements",
            "payload", "readings", "values"};

    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "JSON";
    }

    @Override
    public boolean supports(ReceptionContext context) {
        String contentType = context.getPacket().contentType();
        if (contentType != null && contentType.contains("json")) {
            return true;
        }
        return readTree(context) != null;
    }

    @Override
    protected ParsedTelemetry doParse(ReceptionContext context) throws PayloadParseException {
        JsonNode root = readTree(context);
        if (root == null) {
            throw new PayloadParseException("Payload is not a JSON object");
        }

        ParsedTelemetry.Accumulator readings = new ParsedTelemetry.Accumulator();
        collect(root, readings);
        for (String container : READING_CONTAINERS) {
            JsonNode nested = root.path(container);
            if (nested.isObject()) {
                collect(nested, readings);
            }
        }

        Instant observedAt = firstTimestamp(root,
                "observedAt", "timestamp", "time", "ts", "readingTime", "measuredAt");

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("keys", fieldNames(root));

        Double latitude = number(root, "latitude", "lat");
        Double longitude = number(root, "longitude", "lon", "lng");

        return ParsedTelemetry.builder()
                .observedAt(observedAt)
                .metrics(readings.metrics())
                .alarmFlags(readings.flags())
                .battery(readings.metrics().get(DeviceMessage.Metrics.BATTERY_VOLTAGE))
                .signalStrength(number(root, "rssi", "signalStrength"))
                .signalToNoise(number(root, "snr", "loRaSNR"))
                .frameCounter(integer(root, "fCnt", "frameCounter", "seq", "sequence"))
                .latitude(latitude)
                .longitude(longitude)
                .raw(raw)
                .parser(name())
                .build();
    }

    /**
     * Pulls numeric and boolean fields out of one object level.
     *
     * <p>Only scalars: nested objects are containers, handled by the caller, and arrays are
     * ambiguous — a list of readings has no field name to attach to each element, and guessing
     * would file the wrong metric under the right name, which is worse than not reading it.
     */
    private void collect(JsonNode node, ParsedTelemetry.Accumulator readings) {
        node.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (!value.isNumber() && !value.isBoolean()) {
                return;
            }
            String canonical = MetricVocabulary.canonicalise(entry.getKey());
            if (canonical == null) {
                return;
            }
            double numeric = value.isBoolean() ? (value.asBoolean() ? 1.0 : 0.0) : value.asDouble();
            readings.metric(canonical, numeric);

            String flag = MetricVocabulary.flagFor(canonical, numeric);
            if (flag != null) {
                readings.flag(flag, true, null);
            }
        });
    }

    private static java.util.List<String> fieldNames(JsonNode node) {
        java.util.List<String> names = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private JsonNode readTree(ReceptionContext context) {
        try {
            JsonNode node = objectMapper.readTree(context.getPacket().payload());
            return node != null && node.isObject() ? node : null;
        } catch (Exception notJson) {
            return null;
        }
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
