package com.aquagrid.platform.iot.receiver.domain.model;

import lombok.Builder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One packet as it arrived, before anything is known about it.
 *
 * <p>This is the receiver's universal input, and the reason the pipeline can be written once. A
 * LoRaWAN uplink, an MQTT publish, a UDP datagram and a WebSocket frame differ enormously on the
 * wire and not at all here: bytes, an origin, whatever identifiers and credentials the transport
 * could see, and free-form transport attributes. Every stage after this point is transport-blind.
 *
 * <p>Nothing in it is trusted. The identifiers are <em>claims</em> the device made about itself and
 * the credentials are claims about its authority; both are checked downstream. In particular
 * {@code tenantId} is absent by design — the tenant is pinned from the resolved device row, never
 * taken from the wire, or a gateway could file readings against another municipality.
 *
 * @param packetId      identity of this reception, and of its packet-log row
 * @param transport     the transport code that produced it, e.g. {@code LORAWAN}
 * @param receivedAt    when the receiver took delivery
 * @param payload       the raw bytes, exactly as received
 * @param contentType   media type where the transport declares one; null for raw sockets
 * @param sourceIp      the peer address, resolved through the trusted-proxy rules where applicable
 * @param correlationId trace id linking every log line for this packet
 * @param identifiers   identifier claims the transport could see, by kind
 * @param credentials   credential claims the transport could see
 * @param attributes    transport-specific context — topic, request URI, listening port, session id
 */
@Builder(toBuilder = true)
public record InboundPacket(
        UUID packetId,
        String transport,
        Instant receivedAt,
        byte[] payload,
        String contentType,
        String sourceIp,
        String correlationId,
        Map<IdentifierType, String> identifiers,
        PacketCredentials credentials,
        Map<String, String> attributes
) {

    /** Well-known {@link #attributes} keys, so transports and stages agree on spelling. */
    public static final class Attributes {
        public static final String MQTT_TOPIC = "mqtt.topic";
        public static final String MQTT_CLIENT_ID = "mqtt.clientId";
        public static final String HTTP_PATH = "http.path";
        public static final String HTTP_METHOD = "http.method";
        public static final String USER_AGENT = "http.userAgent";
        public static final String SOCKET_PORT = "socket.port";
        public static final String SESSION_ID = "session.id";
        public static final String GATEWAY_ID = "gateway.id";
        /** Set when a stored packet is being re-injected, so the replay is never mistaken for live. */
        public static final String REPLAY_OF = "replay.of";

        private Attributes() {
        }
    }

    public InboundPacket {
        packetId = packetId == null ? UUID.randomUUID() : packetId;
        receivedAt = receivedAt == null ? Instant.now() : receivedAt;
        payload = payload == null ? new byte[0] : payload.clone();
        credentials = credentials == null ? PacketCredentials.none() : credentials;
        identifiers = identifiers == null || identifiers.isEmpty()
                ? Map.of() : Map.copyOf(new EnumMap<>(identifiers));
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    /**
     * The payload bytes.
     *
     * <p>Copied on the way out as well as in. Parsers run in sequence over the same packet and one
     * of them decrypting or unmasking in place would silently corrupt the next — and the packet log
     * writes these bytes after every parser has run.
     */
    @Override
    public byte[] payload() {
        return payload.clone();
    }

    /** Payload size without copying it. What the size validator and the packet log record. */
    public int size() {
        return payload.length;
    }

    /** The payload as UTF-8 text. Undecodable bytes become replacement characters, never an error. */
    public String payloadAsText() {
        return new String(payload, StandardCharsets.UTF_8);
    }

    public String identifier(IdentifierType type) {
        return identifiers.get(type);
    }

    public String attribute(String key) {
        return attributes.get(key);
    }

    /** True when this packet is a stored packet being re-injected rather than live traffic. */
    public boolean isReplay() {
        return attributes.containsKey(Attributes.REPLAY_OF);
    }

    /** Never renders the payload or the credentials — this is called on the rejection log path. */
    @Override
    public String toString() {
        return "InboundPacket[id=" + packetId + ", transport=" + transport
                + ", bytes=" + payload.length + ", from=" + sourceIp
                + ", identifiers=" + identifiers.keySet()
                + ", credentials=" + credentials.describe() + "]";
    }

    /** Convenience for transports that discover identifiers one at a time. */
    public static final class IdentifierMap {
        private IdentifierMap() {
        }

        public static Map<IdentifierType, String> of(Map<IdentifierType, String> source) {
            Map<IdentifierType, String> result = new EnumMap<>(IdentifierType.class);
            source.forEach((key, value) -> {
                if (value != null && !value.isBlank()) {
                    result.put(key, value.trim());
                }
            });
            return result;
        }
    }

    /** Mutable accumulator for transports that build identifiers and attributes incrementally. */
    public static final class Collector {
        private final Map<IdentifierType, String> identifiers = new EnumMap<>(IdentifierType.class);
        private final Map<String, String> attributes = new LinkedHashMap<>();

        public Collector identifier(IdentifierType type, String value) {
            if (value != null && !value.isBlank()) {
                identifiers.put(type, value.trim());
            }
            return this;
        }

        public Collector attribute(String key, String value) {
            if (value != null && !value.isBlank()) {
                attributes.put(key, value);
            }
            return this;
        }

        public Map<IdentifierType, String> identifiers() {
            return identifiers;
        }

        public Map<String, String> attributes() {
            return attributes;
        }
    }
}
