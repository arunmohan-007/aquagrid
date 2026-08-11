package com.aquagrid.platform.iot.receiver.application.parser;

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
 * Decodes a ChirpStack LoRaWAN uplink envelope.
 *
 * <p>ChirpStack is the open-source LoRaWAN network server, and the mandatory choice for the on-prem
 * and air-gapped deployments this platform targets. Its uplink event wraps the device's own frame
 * in a JSON envelope carrying the DevEUI, frame counter and radio quality, with the frame itself
 * base64-encoded in {@code data}.
 *
 * <p>First in the parser order, because the envelope is unambiguous — nothing else has a
 * {@code deviceInfo.devEui} — and because it arrives on the HTTP transport, where the generic JSON
 * parser would otherwise claim it and produce a reading whose only metric is the frame counter.
 *
 * <p>Two payload conventions are handled, and both occur: the raw frame in {@code data}, and a
 * network-server-side codec's already-decoded output in {@code object}. Where the codec ran, its
 * output is preferred — it had the vendor's own decoder, which knows more about the device than the
 * platform's generic layout does.
 */
@Component
@RequiredArgsConstructor
public class ChirpStackEnvelopeParser extends AbstractPayloadParser {

    public static final int ORDER = 10;

    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "CHIRPSTACK_ENVELOPE";
    }

    @Override
    public boolean supports(ReceptionContext context) {
        JsonNode envelope = readTree(context);
        return envelope != null
                && (envelope.hasNonNull("deviceInfo")
                    || (envelope.has("data") && envelope.has("fCnt")));
    }

    @Override
    protected ParsedTelemetry doParse(ReceptionContext context) throws PayloadParseException {
        JsonNode envelope = readTree(context);
        if (envelope == null) {
            throw new PayloadParseException("Not a JSON envelope");
        }

        Instant observedAt = firstTimestamp(envelope, "time", "publishedAt");
        Integer frameCounter = integer(envelope, "fCnt");
        Double rssi = radio(envelope, "rssi");
        Double snr = radio(envelope, "snr", "loRaSNR");

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("devEui", envelope.path("deviceInfo").path("devEui").asText(null));
        raw.put("fPort", envelope.path("fPort").asInt(0));
        raw.put("dr", envelope.path("dr").asInt(0));

        // A network-server codec already produced structured readings — prefer them.
        JsonNode decoded = envelope.path("object");
        if (decoded.isObject() && !decoded.isEmpty()) {
            ParsedTelemetry.Accumulator readings = new ParsedTelemetry.Accumulator();
            collect(decoded, readings);
            raw.put("source", "networkServerCodec");
            return ParsedTelemetry.builder()
                    .observedAt(observedAt)
                    .metrics(readings.metrics())
                    .alarmFlags(readings.flags())
                    .frameCounter(frameCounter)
                    .signalStrength(rssi)
                    .signalToNoise(snr)
                    .battery(readings.metrics().get(
                            com.aquagrid.platform.iot.api.DeviceMessage.Metrics.BATTERY_VOLTAGE))
                    .raw(raw)
                    .parser(name())
                    .build();
        }

        // Otherwise decode the raw frame ourselves with the shared meter codec.
        byte[] frame = WaterMeterFrameCodec.decodeText(envelope.path("data").asText(""));
        ParsedTelemetry decodedFrame = WaterMeterFrameCodec.decode(frame, name());
        raw.put("source", "frame");
        raw.putAll(decodedFrame.raw());

        return decodedFrame.toBuilder()
                .observedAt(observedAt)
                .frameCounter(frameCounter)
                .signalStrength(rssi)
                .signalToNoise(snr)
                .raw(raw)
                .build();
    }

    /**
     * Radio quality, which ChirpStack reports per receiving gateway rather than per uplink.
     *
     * <p>The best value across gateways is taken, not the first. With several gateways in range the
     * first entry is an arbitrary one, and coverage analysis built on it would show a device as
     * marginal whenever an unlucky gateway happened to be listed first — hiding that it is in fact
     * well covered by another.
     */
    private static Double radio(JsonNode envelope, String... fields) {
        Double direct = number(envelope, fields);
        if (direct != null) {
            return direct;
        }
        JsonNode rxInfo = envelope.path("rxInfo");
        if (!rxInfo.isArray()) {
            return null;
        }
        Double best = null;
        for (JsonNode gateway : rxInfo) {
            Double value = number(gateway, fields);
            if (value != null && (best == null || value > best)) {
                best = value;
            }
        }
        return best;
    }

    private void collect(JsonNode object, ParsedTelemetry.Accumulator readings) {
        object.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            String canonical = MetricVocabulary.canonicalise(entry.getKey());
            if (value.isNumber()) {
                readings.metric(canonical, value.asDouble());
                String flag = MetricVocabulary.flagFor(canonical, value.asDouble());
                if (flag != null) {
                    readings.flag(flag, true, null);
                }
            } else if (value.isBoolean()) {
                readings.metric(canonical, value.asBoolean() ? 1.0 : 0.0);
                String flag = MetricVocabulary.flagFor(canonical, value.asBoolean() ? 1.0 : 0.0);
                if (flag != null) {
                    readings.flag(flag, true, null);
                }
            }
        });
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
