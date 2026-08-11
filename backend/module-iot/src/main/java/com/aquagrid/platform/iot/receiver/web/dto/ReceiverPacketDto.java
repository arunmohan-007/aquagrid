package com.aquagrid.platform.iot.receiver.web.dto;

import com.aquagrid.platform.iot.receiver.domain.model.PacketLog;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One packet as an operator sees it: when it arrived, what the device claimed the time was, and
 * what became of it.
 *
 * <p>Two timestamps, always, and never collapsed into one. {@code receivedAt} is the platform's
 * clock and {@code observedAt} is the device's, and the gap between them is the most diagnostic
 * number on the row — seconds means a healthy link, hours means a device that buffered through an
 * outage, and a negative gap means a device whose clock is wrong. A single "timestamp" column
 * would hide all three.
 *
 * <p>{@code rawPayload} is deliberately absent. It can be a device secret in a badly-designed
 * vendor payload, it is often large, and it is not what this view is for; the packet-detail
 * endpoint serves it separately, behind its own permission.
 */
@Schema(name = "ReceiverPacket", description = "One packet the receiver took delivery of")
public record ReceiverPacketDto(
        @Schema(description = "Packet id — quote this to support to find the full record")
        UUID packetId,
        UUID deviceId,
        @Schema(description = "Operator-facing device code, where the device was resolved")
        String deviceCode,
        @Schema(description = "Transport that delivered it", example = "LORAWAN")
        String transport,
        String communicationProfile,
        @Schema(description = "When the platform took delivery. Always present.")
        Instant receivedAt,
        @Schema(description = "The device's own clock. Null when the payload carried no timestamp.")
        Instant observedAt,
        @Schema(description = "Seconds between the device's clock and ours. Negative means the "
                + "device is ahead. Null when the payload carried no timestamp.")
        Long latencySeconds,
        @Schema(description = "ACCEPTED, DUPLICATE or REJECTED")
        String status,
        @Schema(description = "Platform error code; null when accepted", example = "RECEIVER_UNKNOWN_DEVICE")
        String errorCode,
        String errorDetail,
        int payloadSize,
        int processingTimeMs,
        String authenticationScheme,
        @Schema(description = "Which strategy identified the device")
        String resolutionStrategy,
        String parser,
        String sourceIp,
        @Schema(description = "Trace id linking this to the application log")
        String correlationId,
        @Schema(description = "Metric values recorded from this packet, keyed by canonical name. "
                + "Empty for a keep-alive or a rejected packet.")
        Map<String, Double> readings
) {

    public static ReceiverPacketDto from(PacketLog log, String deviceCode,
                                         Map<String, Double> readings) {
        return new ReceiverPacketDto(
                log.getId(),
                log.getDeviceId(),
                deviceCode,
                log.getTransport(),
                log.getCommunicationProfile(),
                log.getReceivedAt(),
                log.getObservedAt(),
                latency(log),
                log.getStatus() == null ? null : log.getStatus().name(),
                log.getErrorCode(),
                log.getErrorDetail(),
                log.getPayloadSize(),
                log.getProcessingTimeMs(),
                log.getAuthenticationScheme(),
                log.getResolutionStrategy(),
                log.getParser(),
                log.getSourceIp(),
                log.getCorrelationId(),
                readings == null ? Map.of() : readings);
    }

    /** Computed rather than stored: it is derivable, and a stored copy would be one more thing
     *  that can disagree with the two columns it comes from. */
    private static Long latency(PacketLog log) {
        if (log.getObservedAt() == null || log.getReceivedAt() == null) {
            return null;
        }
        return java.time.Duration.between(log.getObservedAt(), log.getReceivedAt()).toSeconds();
    }
}
