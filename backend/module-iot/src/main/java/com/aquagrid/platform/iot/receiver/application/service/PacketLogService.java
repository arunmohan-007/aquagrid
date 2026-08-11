package com.aquagrid.platform.iot.receiver.application.service;

import com.aquagrid.platform.iot.domain.model.Device;
import com.aquagrid.platform.iot.receiver.application.resolver.DeviceResolver;
import com.aquagrid.platform.iot.receiver.domain.model.PacketLog;
import com.aquagrid.platform.iot.receiver.domain.model.ParsedTelemetry;
import com.aquagrid.platform.iot.receiver.domain.model.ReceptionContext;
import com.aquagrid.platform.iot.receiver.domain.model.ReceptionStatus;
import com.aquagrid.platform.iot.receiver.infrastructure.config.ReceiverProperties;
import com.aquagrid.platform.iot.receiver.infrastructure.persistence.PacketLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writes the per-packet forensic record.
 *
 * <p>Runs in its own transaction, and that is the whole reason this is a service rather than three
 * lines in the dispatcher. The log must be written <em>whatever</em> happened to the packet — most
 * of all when the reception failed — and a rejection that rolled back its own evidence would leave
 * the platform unable to answer why a meter went quiet. {@code REQUIRES_NEW} makes the row
 * independent of the outcome it describes.
 *
 * <p>It is also why a failure to write the log is swallowed. The log is important; it is not more
 * important than the reading. If the log insert fails, the telemetry has already been committed by
 * its own transaction, and throwing here would report a successful ingestion as a failure and
 * invite the gateway to send it again.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PacketLogService {

    private final PacketLogRepository repository;
    private final ReceiverProperties properties;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(ReceptionContext context, ReceptionStatus status) {
        try {
            repository.save(build(context, status));
        } catch (RuntimeException e) {
            log.error("Failed to write packet log for {} — the reading itself is unaffected",
                    context.packetId(), e);
        }
    }

    private PacketLog build(ReceptionContext context, ReceptionStatus status) {
        PacketLog entry = new PacketLog();
        entry.setId(context.packetId());
        entry.setTransport(context.transport());
        entry.setReceivedAt(context.getPacket().receivedAt());
        entry.setStatus(status);
        entry.setPayloadSize(context.getPacket().size());
        entry.setSourceIp(context.getPacket().sourceIp());
        entry.setCorrelationId(context.correlationId());
        entry.setContentType(context.getPacket().contentType());
        entry.setProcessingTimeMs((int) Math.min(Integer.MAX_VALUE, context.elapsedMillis()));
        entry.setAuthenticationScheme(context.getAuthenticationScheme());
        entry.setPrincipal(truncate(context.getAuthenticatedPrincipal(), 120));
        entry.setResolutionStrategy(DeviceResolver.strategyOf(context));

        Device device = context.getDevice();
        if (device != null) {
            entry.setDeviceId(device.getId());
            entry.setOrganizationId(device.getOrganizationId());
        }
        if (context.getProfile() != null) {
            entry.setCommunicationProfile(context.getProfile().name());
        }
        ParsedTelemetry telemetry = context.getTelemetry();
        if (telemetry != null) {
            entry.setObservedAt(telemetry.observedAt());
            entry.setParser(telemetry.parser());
        }
        if (context.isRejected()) {
            entry.setErrorCode(context.getRejection().code().name());
            entry.setErrorDetail(truncate(context.getRejection().detail(), 500));
        }
        if (shouldStorePayload(status)) {
            entry.setRawPayload(context.getPacket().payload());
        }
        entry.setMetadata(metadata(context));
        return entry;
    }

    /**
     * Rejected payloads are kept by default and accepted ones are not.
     *
     * <p>The asymmetry is deliberate: an accepted payload's information is already in the readings
     * table, so storing it doubles the cost of telemetry to say the same thing twice. A rejected
     * one's information exists nowhere else at all — discard it and the only record of why the
     * packet was refused is a truncated error string.
     */
    private boolean shouldStorePayload(ReceptionStatus status) {
        ReceiverProperties.Retention retention = properties.retention();
        return status == ReceptionStatus.REJECTED
                ? retention.storeRejectedPayloads()
                : retention.storeAcceptedPayloads();
    }

    private static Map<String, Object> metadata(ReceptionContext context) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        context.getNotes().forEach((key, value) -> {
            if (value != null) {
                metadata.put(key, value);
            }
        });
        // Per-stage timings, so a latency regression can be attributed from stored data rather than
        // by reproducing the traffic.
        Map<String, Object> timings = new LinkedHashMap<>();
        context.getStageNanos().forEach((stage, nanos) -> timings.put(stage, nanos / 1_000_000L));
        metadata.put("stageMillis", timings);
        context.getPacket().attributes().forEach((key, value) -> metadata.put("attr." + key, value));
        return metadata;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
