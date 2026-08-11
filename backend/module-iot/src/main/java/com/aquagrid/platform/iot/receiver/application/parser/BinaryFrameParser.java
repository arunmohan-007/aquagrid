package com.aquagrid.platform.iot.receiver.application.parser;

import com.aquagrid.platform.iot.domain.model.CommunicationProfile;
import com.aquagrid.platform.iot.receiver.domain.model.ParsedTelemetry;
import com.aquagrid.platform.iot.receiver.domain.model.ReceptionContext;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Decodes a raw meter frame arriving as bytes.
 *
 * <p>The socket transports' parser, and LoRaWAN's when the frame reaches the receiver without a
 * network-server envelope. All of them carry the same thing — the meter's own frame — so all of
 * them delegate to {@link WaterMeterFrameCodec} rather than each owning a copy of the layout.
 *
 * <p>Selection is by communication profile rather than by transport, which is what lets one parser
 * serve four transports and lets a LoRaWAN device keep working when its network server is
 * reconfigured to forward raw frames.
 */
@Component
public class BinaryFrameParser extends AbstractPayloadParser {

    public static final int ORDER = 30;

    /** Profiles whose payload is a raw meter frame rather than a structured document. */
    private static final Set<CommunicationProfile> BINARY_PROFILES = Set.of(
            CommunicationProfile.LORAWAN,
            CommunicationProfile.TCP,
            CommunicationProfile.UDP);

    @Override
    public String name() {
        return "BINARY_FRAME";
    }

    @Override
    public boolean supports(ReceptionContext context) {
        if (context.getProfile() != null && BINARY_PROFILES.contains(context.getProfile())) {
            return true;
        }
        String contentType = context.getPacket().contentType();
        return contentType != null && contentType.startsWith("application/octet-stream");
    }

    @Override
    protected ParsedTelemetry doParse(ReceptionContext context) {
        byte[] payload = context.getPacket().payload();

        // A socket transport may deliver the frame hex-encoded — plenty of RTUs send ASCII hex over
        // TCP because it survives being read in a terminal. Detected rather than configured: a
        // payload that is entirely hex characters and decodes to a plausible frame length is one.
        byte[] frame = looksLikeText(payload)
                ? WaterMeterFrameCodec.decodeText(new String(payload,
                    java.nio.charset.StandardCharsets.US_ASCII).trim())
                : payload;

        return WaterMeterFrameCodec.decode(frame.length == 0 ? payload : frame, name());
    }

    /**
     * Whether the bytes are printable ASCII.
     *
     * <p>Cheap and sufficient: a binary meter frame contains a 32-bit volume counter, so it is
     * almost certain to include a byte outside the printable range within the first few bytes. The
     * failure mode is benign either way — a frame misread as text decodes to nothing and falls back
     * to the raw bytes.
     */
    private static boolean looksLikeText(byte[] payload) {
        if (payload.length == 0) {
            return false;
        }
        for (byte b : payload) {
            int value = b & 0xFF;
            boolean printable = value >= 0x20 && value <= 0x7E;
            boolean whitespace = value == '\n' || value == '\r' || value == '\t';
            if (!printable && !whitespace) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
