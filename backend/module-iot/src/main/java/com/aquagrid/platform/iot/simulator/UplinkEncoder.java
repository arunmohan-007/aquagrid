package com.aquagrid.platform.iot.simulator;

import com.aquagrid.platform.iot.domain.model.CommunicationProfile;
import com.aquagrid.platform.iot.receiver.domain.model.IdentifierType;
import com.aquagrid.platform.iot.receiver.domain.model.InboundPacket;
import com.aquagrid.platform.iot.receiver.infrastructure.config.ReceiverModuleConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Base64;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static com.aquagrid.platform.iot.receiver.application.parser.WaterMeterFrameCodec.encode;

/**
 * Turns one simulated reading into a packet shaped like the network the device claims to be on.
 *
 * <p>This class is where the simulator earns the word "validation" in its brief. Emitting a tidy
 * internal object would exercise persistence and nothing else; the interesting half of the ingestion
 * path — envelope recognition, frame decoding, endianness, vendor-field canonicalisation, parser
 * selection — only runs when the bytes look like what a real network delivers. So a LoRaWAN meter
 * produces a ChirpStack envelope with a base64 frame in it, a socket meter produces the raw frame,
 * and a webhook meter produces a JSON document using vendor spellings the vocabulary has to
 * canonicalise. Three parsers, exercised by a fleet nobody had to build a gateway for.
 *
 * <p>The identifier is declared the way each network declares it — a DevEUI for LoRaWAN, an IMEI for
 * cellular — rather than always as the pre-normalised {@code NETWORK_ADDRESS}. Always using the fast
 * path would leave the resolver's candidate ordering, the part that actually breaks when a strategy
 * is added, permanently untested.
 *
 * <p><b>Nothing on the packet says "simulated".</b> No marker attribute, no transport code of its
 * own, no correlation-id prefix — the packet is indistinguishable from the physical device's, which
 * is the requirement: the rest of the platform learns that a reading is synthetic from the device's
 * {@code source} column and from nowhere else. A flag on the wire would be a second answer to that
 * question, and the first thing to be forgotten by a query written six months from now.
 *
 * <p>What it does not fake is the origin: {@code sourceIp} is loopback because that is genuinely
 * where the packet came from, and the credentials are the device's own real ones. Spoofing a gateway
 * address would put a falsehood in the packet log an operator reads during an incident.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "aquagrid.iot.transports", name = "simulator", havingValue = "true")
public class UplinkEncoder {

    /** The packet's origin. In-process delivery genuinely originates on the loopback interface. */
    public static final String SOURCE_IP = "127.0.0.1";

    /** The gateway a simulated LoRaWAN uplink is reported as having been heard by. */
    private static final String LORAWAN_GATEWAY_ID = "sim-gateway-01";

    private static final String JSON = "application/json";
    private static final String OCTET_STREAM = "application/octet-stream";

    private final ObjectMapper objectMapper;
    private final DeviceCredentialPresenter credentials;
    private final ConfiguredParameterComposer parameterComposer;

    /**
     * Builds the packet for one meter's reading.
     *
     * @param meter    the meter, for identity and fault state
     * @param flowLpm  the flow to report. Negative under a reverse-flow fault
     * @param now      the observation instant, used as the device's own clock reading
     */
    public InboundPacket encodeUplink(SimulatedMeter meter, double flowLpm, Instant now) {
        CommunicationProfile profile = meter.profile();
        int frameCounter = meter.nextFrameCounter();
        double battery = meter.drainBattery();
        double volume = meter.cumulativeVolume();

        // Radio quality, derived from the address so a meter's link stays characteristically good or
        // bad across runs. A fleet whose RSSI was uniform random would make coverage analysis
        // meaningless — every meter would average the same.
        int spread = Math.floorMod(meter.networkAddress().hashCode(), 20);
        double rssi = -80.0 + spread;
        double snr = 7.0 + spread % 5;

        Payload payload = switch (profile) {
            case LORAWAN -> chirpStackEnvelope(meter, flowLpm, volume, battery, frameCounter, rssi, snr, now);
            case TCP, UDP -> new Payload(meterFrame(meter, flowLpm, volume, battery, now), OCTET_STREAM);
            case null, default -> jsonDocument(meter, flowLpm, volume, battery, frameCounter,
                    radioBearing(profile) ? rssi : null, radioBearing(profile) ? snr : null, now);
        };

        return InboundPacket.builder()
                .packetId(UUID.randomUUID())
                // The network the device is registered on, not "SIMULATOR". A packet tagged with a
                // transport no real device speaks would be routed by no real rule, would be missing
                // from the transport statistics an operator reads, and would have to be renamed on
                // the day the physical device took over.
                .transport(profile == null ? "HTTP" : profile.name())
                .receivedAt(now)
                .payload(payload.bytes())
                .contentType(payload.contentType())
                .sourceIp(SOURCE_IP)
                .correlationId(UUID.randomUUID().toString())
                .identifiers(identifiers(meter, profile))
                .credentials(credentials.credentialsFor(meter, payload.bytes(), now))
                .attributes(attributes(meter, profile))
                .build();
    }

    /** A payload and the media type that tells the parser chain how to read it. */
    private record Payload(byte[] bytes, String contentType) {
    }

    /**
     * The ChirpStack uplink event, as the LoRaWAN network server delivers it.
     *
     * <p>Radio quality goes in {@code rxInfo} rather than at the top level because that is where
     * ChirpStack puts it — per receiving gateway. The parser takes the best value across gateways,
     * and a payload that offered only one flat number would never exercise that.
     */
    private Payload chirpStackEnvelope(SimulatedMeter meter, double flowLpm, double volume,
                                       double battery, int frameCounter, double rssi, double snr,
                                       Instant now) {
        ObjectNode envelope = objectMapper.createObjectNode();
        ObjectNode deviceInfo = envelope.putObject("deviceInfo");
        deviceInfo.put("devEui", meter.networkAddress().toLowerCase());
        deviceInfo.put("deviceName", meter.deviceCode());

        envelope.put("fCnt", frameCounter);
        envelope.put("fPort", 2);
        envelope.put("dr", 5);
        envelope.put("time", now.toString());
        envelope.put("data", Base64.getEncoder()
                .encodeToString(meterFrame(meter, flowLpm, volume, battery, now)));

        ArrayNode rxInfo = envelope.putArray("rxInfo");
        ObjectNode gateway = rxInfo.addObject();
        gateway.put("gatewayId", LORAWAN_GATEWAY_ID);
        gateway.put("rssi", (int) Math.round(rssi));
        gateway.put("snr", round(snr));

        return new Payload(writeJson(envelope), JSON);
    }

    /**
     * A JSON telemetry document, in the shape a carrier webhook or an HTTP-pushing meter sends.
     *
     * <p>Vendor spellings on purpose — {@code flowRate}, {@code battery}, {@code totalVolume} — so
     * {@code MetricVocabulary} has to canonicalise them. A document written in the platform's own
     * canonical names would pass through the vocabulary without touching it, which is the one part
     * of this path that silently breaks when a mapping is removed.
     */
    private Payload jsonDocument(SimulatedMeter meter, double flowLpm, double volume, double battery,
                                 int frameCounter, Double rssi, Double snr, Instant now) {
        ObjectNode document = objectMapper.createObjectNode();
        document.put("deviceId", meter.networkAddress());
        document.put("timestamp", now.toString());
        document.put("seq", frameCounter);
        document.put("flowRate", round(flowLpm));
        document.put("totalVolume", round(volume));
        document.put("battery", round(battery));
        if (meter.isTampered()) {
            document.put("tamperAlarm", true);
        }
        if (meter.isReverseFlow()) {
            document.put("backflow", true);
        }
        if (meter.isLeaking()) {
            document.put("leakAlarm", true);
        }
        if (rssi != null) {
            document.put("rssi", (int) Math.round(rssi));
            document.put("snr", round(snr));
        }
        /*
         * Everything else the device's data configuration says it reports — voltage on a pump
         * monitor, level on a level sensor — plus, when a developer has switched it on, fields that
         * are deliberately not configured. Added last so they cannot displace the meter model's own
         * numbers, and added as ordinary keys: nothing here distinguishes a composed field from one
         * the water-meter model produced, because nothing downstream should be able to tell.
         *
         * Only the JSON document carries them. The binary frame has a fixed layout that a physical
         * meter's firmware defines, and the ChirpStack envelope wraps that frame — inventing extra
         * fields in either would be simulating a device that could not exist.
         */
        parameterComposer.compose(meter, frameCounter, fieldNames(document))
                .forEach((key, value) -> putScalar(document, key, value));
        return new Payload(writeJson(document), JSON);
    }

    /** What the meter model has already written, so composition can never displace it. */
    private static java.util.Set<String> fieldNames(ObjectNode document) {
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        document.fieldNames().forEachRemaining(names::add);
        return names;
    }

    /** Writes a composed value under the JSON type it actually is, rather than as a string. */
    private static void putScalar(ObjectNode document, String key, Object value) {
        switch (value) {
            case Boolean flag -> document.put(key, flag);
            case Long whole -> document.put(key, whole);
            case Integer whole -> document.put(key, whole);
            case Double decimal -> document.put(key, decimal);
            case Number number -> document.put(key, number.doubleValue());
            default -> document.put(key, String.valueOf(value));
        }
    }

    /** The meter's own binary frame — the same layout on every network that carries it raw. */
    private byte[] meterFrame(SimulatedMeter meter, double flowLpm, double volume, double battery,
                              Instant now) {
        return encode(volume, flowLpm, battery,
                meter.isTampered(), meter.isReverseFlow(), meter.isLeaking(),
                meter.isBursting(now));
    }

    /**
     * The identifier claim, declared the way this network declares it.
     *
     * <p>{@code NETWORK_ADDRESS} is always included alongside as the fallback, because a device on a
     * transport with no radio identity — MQTT, HTTP, a socket — has nothing else to be found by, and
     * because a fleet that could not be resolved would fail as an unknown device rather than as the
     * configuration mistake it actually is.
     */
    private static Map<IdentifierType, String> identifiers(SimulatedMeter meter,
                                                           CommunicationProfile profile) {
        Map<IdentifierType, String> claims = new EnumMap<>(IdentifierType.class);
        claims.put(IdentifierType.NETWORK_ADDRESS, meter.networkAddress());
        if (profile != null) {
            switch (profile) {
                case LORAWAN -> claims.put(IdentifierType.DEV_EUI, meter.networkAddress());
                case NB_IOT, CELLULAR -> claims.put(IdentifierType.IMEI, meter.networkAddress());
                case MQTT -> claims.put(IdentifierType.MQTT_CLIENT_ID, meter.networkAddress());
                default -> {
                    // ETHERNET, TCP, UDP and WEBSOCKET address the device by the value
                    // already claimed above; there is no second name for it to travel under.
                }
            }
        }
        return claims;
    }

    /**
     * The transport context a real carrier would have supplied.
     *
     * <p>Populated because stages read it. {@code MqttTopicStrategy} resolves a device from the
     * topic alone — the shared-bridge arrangement where the client id names the bridge and only the
     * topic names the meter — and a simulated MQTT fleet that omitted the topic would leave that
     * strategy, and the whole bridge shape it exists to support, untested.
     */
    private static Map<String, String> attributes(SimulatedMeter meter,
                                                  CommunicationProfile profile) {
        Map<String, String> attributes = new LinkedHashMap<>();
        if (profile == CommunicationProfile.MQTT) {
            attributes.put(InboundPacket.Attributes.MQTT_CLIENT_ID, meter.networkAddress());
            Object topic = meter.provisioning().get("topic");
            if (topic != null) {
                attributes.put(InboundPacket.Attributes.MQTT_TOPIC, String.valueOf(topic));
            }
        } else if (profile == CommunicationProfile.TCP || profile == CommunicationProfile.UDP) {
            attributes.put(InboundPacket.Attributes.SESSION_ID, meter.deviceId().toString());
        } else {
            // Webhook-delivered transports arrive as an HTTP POST on the receiver's ingress route
            // for that transport — the same URL the network server or carrier will call.
            attributes.put(InboundPacket.Attributes.HTTP_METHOD, "POST");
            attributes.put(InboundPacket.Attributes.HTTP_PATH,
                    ReceiverModuleConfig.INGRESS_BASE + "/"
                            + (profile == null ? "http" : profile.name().toLowerCase()));
        }
        if (profile == CommunicationProfile.LORAWAN) {
            attributes.put(InboundPacket.Attributes.GATEWAY_ID, LORAWAN_GATEWAY_ID);
        }
        return attributes;
    }

    /** Whether this network reports radio quality at all. A wired or brokered link does not. */
    private static boolean radioBearing(CommunicationProfile profile) {
        return profile == CommunicationProfile.NB_IOT || profile == CommunicationProfile.CELLULAR;
    }

    private byte[] writeJson(ObjectNode node) {
        try {
            return objectMapper.writeValueAsBytes(node);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // Serialising an ObjectNode this class built itself cannot fail for data reasons; if it
            // does, the mapper is misconfigured and every transport is affected, not just this one.
            throw new IllegalStateException("Simulator could not serialise its own payload", e);
        }
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
