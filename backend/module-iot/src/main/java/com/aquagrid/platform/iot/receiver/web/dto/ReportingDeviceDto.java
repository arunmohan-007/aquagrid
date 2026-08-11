package com.aquagrid.platform.iot.receiver.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * One device's reporting behaviour, summarised — the "who is sending, and when did they last" view.
 *
 * <p>Built from {@code receiver_packet_statistics} rather than by scanning the packet log, because
 * the honest form of this question ("count every packet from every device over the last N hours")
 * is a scan of the largest table in the module on every dashboard refresh. The rollup makes it an
 * indexed read.
 *
 * <p>{@code silentFor} is the field this view exists for. A device with a healthy acceptance rate
 * that last spoke nine hours ago on a fifteen-minute schedule is the fault worth finding, and it is
 * invisible in any view that only shows what did arrive.
 */
@Schema(name = "ReportingDevice", description = "A device's recent reporting behaviour")
public record ReportingDeviceDto(
        UUID deviceId,
        String deviceCode,
        String name,
        String transport,
        @Schema(description = "LIVE or SIMULATOR")
        String deviceSource,
        String status,
        @Schema(description = "When the receiver last took a packet from this device")
        Instant lastPacketAt,
        @Schema(description = "Seconds since the last packet. The number that identifies a device "
                + "that has gone quiet — null when it has never reported at all.")
        Long silentForSeconds,
        long accepted,
        long duplicates,
        long rejected,
        @Schema(description = "Accepted as a fraction of all packets, 0-1. Duplicates are excluded "
                + "from the denominator: a retransmission is not a failure, and counting it as one "
                + "would make a device on a lossy but working link look broken.")
        Double successRate,
        long bytesReceived
) {

    public static ReportingDeviceDto of(UUID deviceId, String deviceCode, String name,
                                        String transport, String deviceSource, String status,
                                        Instant lastPacketAt, long accepted, long duplicates,
                                        long rejected, long bytesReceived, Instant now) {
        long judged = accepted + rejected;
        return new ReportingDeviceDto(
                deviceId, deviceCode, name, transport, deviceSource, status,
                lastPacketAt,
                lastPacketAt == null ? null : Duration.between(lastPacketAt, now).toSeconds(),
                accepted, duplicates, rejected,
                judged == 0 ? null : (double) accepted / judged,
                bytesReceived);
    }
}
