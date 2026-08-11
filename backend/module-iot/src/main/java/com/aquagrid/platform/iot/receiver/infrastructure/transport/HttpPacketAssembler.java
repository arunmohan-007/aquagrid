package com.aquagrid.platform.iot.receiver.infrastructure.transport;

import com.aquagrid.platform.common.web.ClientIpResolver;
import com.aquagrid.platform.common.web.CorrelationIdFilter;
import com.aquagrid.platform.iot.receiver.domain.model.IdentifierType;
import com.aquagrid.platform.iot.receiver.domain.model.InboundPacket;
import com.aquagrid.platform.iot.receiver.domain.model.PacketCredentials;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Turns an HTTP request into an {@link InboundPacket}.
 *
 * <p>All the transport-specific knowledge for the HTTP family lives here, in one place: which
 * headers carry credentials, which body fields and headers might name a device. It reads
 * generously and trusts nothing — every value it extracts is a <em>claim</em> that the pipeline
 * then verifies.
 *
 * <p>Reading identifiers from several places at once is deliberate and safe. The resolver tries
 * them in order of trustworthiness and prefers anything an authenticator proved, so offering a
 * DevEUI found in a JSON body costs nothing when a device token has already settled the identity —
 * and rescues the case where the body is all there is.
 *
 * <p>The source address comes from {@link ClientIpResolver}, never from {@code X-Forwarded-For}
 * directly. IP allow-listing and per-source rate limiting both key on it, and a value taken
 * straight from a header would let a caller present a different one on every packet and evade both.
 */
@Component
public class HttpPacketAssembler {

    /** Header names, spanning the conventions the integrated gateways actually use. */
    private static final String[] API_KEY_HEADERS =
            {"X-API-Key", "X-Api-Key", "Api-Key", "X-Gateway-Key"};
    private static final String[] DEVICE_TOKEN_HEADERS =
            {"X-Device-Token", "X-Device-Key"};
    private static final String[] SIGNATURE_HEADERS =
            {"X-Signature", "X-Hub-Signature-256", "X-Payload-Signature"};

    private final ObjectMapper objectMapper;
    private final ClientIpResolver clientIpResolver;

    /** Takes the kernel's {@link ClientIpResolver} bean — the trusted-proxy policy is configured
     *  once, in {@code WebConfig}, and a second instance here would be a second policy to keep in
     *  step with it. */
    public HttpPacketAssembler(ObjectMapper objectMapper, ClientIpResolver clientIpResolver) {
        this.objectMapper = objectMapper;
        this.clientIpResolver = clientIpResolver;
    }

    public InboundPacket assemble(String transport, byte[] body, HttpServletRequest request) {
        InboundPacket.Collector collector = new InboundPacket.Collector();

        collector.attribute(InboundPacket.Attributes.HTTP_PATH, request.getRequestURI())
                .attribute(InboundPacket.Attributes.HTTP_METHOD, request.getMethod())
                .attribute(InboundPacket.Attributes.USER_AGENT,
                        request.getHeader(HttpHeaders.USER_AGENT));

        // Identifier claims from headers — the cheapest source, and the one a constrained device
        // that cannot shape its body can still use.
        collector.identifier(IdentifierType.DEV_EUI, request.getHeader("X-Device-Eui"))
                .identifier(IdentifierType.IMEI, request.getHeader("X-Imei"))
                .identifier(IdentifierType.SERIAL_NUMBER, request.getHeader("X-Serial-Number"))
                .identifier(IdentifierType.DEVICE_CODE, request.getHeader("X-Device-Code"));

        collectFromBody(body, collector);

        PacketCredentials credentials = PacketCredentials.builder()
                .with(PacketCredentials.Keys.API_KEY, firstHeader(request, API_KEY_HEADERS))
                .with(PacketCredentials.Keys.DEVICE_TOKEN, firstHeader(request, DEVICE_TOKEN_HEADERS))
                .with(PacketCredentials.Keys.SIGNATURE, firstHeader(request, SIGNATURE_HEADERS))
                .with(PacketCredentials.Keys.SIGNATURE_ALGORITHM, request.getHeader("X-Signature-Algorithm"))
                .with(PacketCredentials.Keys.NONCE, request.getHeader("X-Nonce"))
                .with(PacketCredentials.Keys.TIMESTAMP, request.getHeader("X-Timestamp"))
                .with(PacketCredentials.Keys.BEARER_TOKEN, bearerToken(request))
                .build();

        return InboundPacket.builder()
                .packetId(UUID.randomUUID())
                .transport(transport)
                .receivedAt(java.time.Instant.now())
                .payload(body)
                .contentType(request.getContentType())
                .sourceIp(clientIpResolver.resolve(request))
                .correlationId(MDC.get(CorrelationIdFilter.TRACE_ID_KEY))
                .identifiers(collector.identifiers())
                .credentials(credentials)
                .attributes(collector.attributes())
                .build();
    }

    /**
     * Identifier claims from the JSON body.
     *
     * <p>Parsed here as well as in the parser, and the duplication is worth its cost: device
     * resolution happens before parsing — it has to, because the parser is chosen by the device's
     * communication profile — so the identifiers must be available earlier than the parse. The
     * work is bounded to reading a handful of top-level fields, and a body that is not JSON is
     * simply skipped.
     */
    private void collectFromBody(byte[] body, InboundPacket.Collector collector) {
        if (body == null || body.length == 0) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            if (!root.isObject()) {
                return;
            }
            collector.identifier(IdentifierType.DEV_EUI,
                            text(root, "devEui", "deviceEui", "dev_eui"))
                    .identifier(IdentifierType.IMEI, text(root, "imei", "deviceId"))
                    .identifier(IdentifierType.SERIAL_NUMBER, text(root, "serialNumber", "serial"))
                    .identifier(IdentifierType.DEVICE_CODE, text(root, "deviceCode"))
                    .identifier(IdentifierType.JOIN_EUI, text(root, "joinEui", "appEui"));

            // ChirpStack nests the device identity one level down.
            JsonNode deviceInfo = root.path("deviceInfo");
            if (deviceInfo.isObject()) {
                collector.identifier(IdentifierType.DEV_EUI, text(deviceInfo, "devEui"))
                        .identifier(IdentifierType.JOIN_EUI, text(deviceInfo, "joinEui"))
                        .identifier(IdentifierType.DEVICE_CODE, text(deviceInfo, "deviceName"));
            }
        } catch (Exception notJson) {
            // A binary or malformed body carries no identifier claims to read. Not an error here —
            // the parser will have its own say, and a header or a credential may still place it.
        }
    }

    private static String text(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isTextual() && !value.asText().isBlank()) {
                return value.asText().trim();
            }
        }
        return null;
    }

    private static String firstHeader(HttpServletRequest request, String... names) {
        for (String name : names) {
            String value = request.getHeader(name);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static String bearerToken(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null) {
            return null;
        }
        // Case-insensitive scheme: RFC 7235 says the scheme is case-insensitive, and gateways in
        // the field send "bearer" as often as "Bearer".
        if (authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return authorization.substring(7).trim();
        }
        return null;
    }
}
