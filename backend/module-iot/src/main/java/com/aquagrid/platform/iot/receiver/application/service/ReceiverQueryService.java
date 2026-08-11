package com.aquagrid.platform.iot.receiver.application.service;

import com.aquagrid.platform.common.error.ResourceNotFoundException;
import com.aquagrid.platform.common.web.PageResponse;
import com.aquagrid.platform.iot.domain.model.Device;
import com.aquagrid.platform.iot.domain.model.DeviceReading;
import com.aquagrid.platform.iot.infrastructure.persistence.DeviceReadingRepository;
import com.aquagrid.platform.iot.infrastructure.persistence.DeviceRepository;
import com.aquagrid.platform.iot.receiver.domain.model.PacketLog;
import com.aquagrid.platform.iot.receiver.domain.model.PacketStatistics;
import com.aquagrid.platform.iot.receiver.infrastructure.persistence.PacketLogRepository;
import com.aquagrid.platform.iot.receiver.infrastructure.persistence.PacketStatisticsRepository;
import com.aquagrid.platform.iot.receiver.web.dto.ReceiverPacketDto;
import com.aquagrid.platform.iot.receiver.web.dto.ReportingDeviceDto;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read side of the receiver: what each device sent, and when.
 *
 * <p>Every method takes the tenant as a parameter and every query scopes by it. That is not
 * defensive habit — {@code receiver_packet_logs} is deliberately not tenant-filtered at the
 * database level, because it must be able to hold rows belonging to no tenant at all (packets from
 * devices nobody registered). The Hibernate tenant filter therefore cannot cover this table, and an
 * unscoped query here would page one utility's operator straight through another's traffic.
 */
@Service
@RequiredArgsConstructor
public class ReceiverQueryService {

    private final PacketLogRepository packetLogs;
    private final PacketStatisticsRepository statistics;
    private final DeviceReadingRepository readings;
    private final DeviceRepository devices;

    /**
     * Cross-device packet search.
     *
     * <p>Returns packets <em>without</em> their readings, and the omission is a deliberate
     * performance boundary rather than an oversight. Attaching values would mean a readings query
     * per device on the page — up to one per row — to answer a question this view is not asking.
     * The per-device feed, which covers one device over one window, is where values belong and can
     * be fetched in a single query.
     */
    @Transactional(readOnly = true)
    public PageResponse<ReceiverPacketDto> search(UUID organizationId, UUID deviceId,
                                                  String transport, String status, String errorCode,
                                                  Instant from, Instant to, Pageable pageable) {
        Page<PacketLog> page = packetLogs.search(organizationId, deviceId, transport, status,
                errorCode, from, to, pageable);

        // One lookup for every device on the page, so the response can carry the operator-facing
        // code rather than only a UUID — resolved in bulk instead of per row.
        Map<UUID, String> codes = deviceCodes(page.getContent());

        return PageResponse.of(page, log -> ReceiverPacketDto.from(
                log, codes.get(log.getDeviceId()), Map.of()));
    }

    /**
     * What one device sent, in order, with the values it carried.
     *
     * <p>The readings are fetched once for the window the page actually covers and grouped back
     * onto their packets by {@code observedAt} — see {@code DeviceReadingRepository.findAllInWindow}
     * for why that reconstruction is exact. One query for the page, not one per packet.
     */
    @Transactional(readOnly = true)
    public PageResponse<ReceiverPacketDto> deviceFeed(UUID organizationId, UUID deviceId,
                                                      Instant from, Instant to, Pageable pageable) {
        Device device = devices.findById(deviceId)
                .filter(candidate -> candidate.getOrganizationId().equals(organizationId))
                // Filtered rather than compared after the fact: a device belonging to another
                // tenant must be indistinguishable from one that does not exist, or this endpoint
                // becomes a way to test which device ids are real.
                .orElseThrow(() -> new ResourceNotFoundException("Device", deviceId));

        Page<PacketLog> page = packetLogs.search(organizationId, deviceId, null, null, null,
                from, to, pageable);
        if (page.isEmpty()) {
            return PageResponse.of(page, log -> ReceiverPacketDto.from(log, device.getDeviceCode(),
                    Map.of()));
        }

        Map<Instant, Map<String, Double>> byObservedAt = readingsFor(deviceId, page.getContent());

        return PageResponse.of(page, log -> ReceiverPacketDto.from(
                log,
                device.getDeviceCode(),
                log.getObservedAt() == null
                        ? Map.of()
                        : byObservedAt.getOrDefault(log.getObservedAt(), Map.of())));
    }

    /**
     * Which devices are reporting, and when each last did.
     *
     * <p>Read from the hourly rollups rather than the packet log. The question spans every device
     * over a long window, which against the raw log is a scan of the biggest table in the module —
     * on a screen that refreshes.
     */
    @Transactional(readOnly = true)
    public List<ReportingDeviceDto> reportingDevices(UUID organizationId, Instant since) {
        Map<UUID, long[]> totals = new HashMap<>();
        Map<UUID, Instant> lastSeen = new HashMap<>();

        for (PacketStatistics bucket : statistics
                .findByOrganizationIdAndBucketStartGreaterThanEqual(organizationId, since)) {
            long[] counters = totals.computeIfAbsent(bucket.getDeviceId(), key -> new long[4]);
            counters[0] += bucket.getAccepted();
            counters[1] += bucket.getDuplicates();
            counters[2] += bucket.getRejected();
            counters[3] += bucket.getBytesReceived();
            lastSeen.merge(bucket.getDeviceId(), bucket.getLastPacketAt(),
                    (a, b) -> b == null || (a != null && a.isAfter(b)) ? a : b);
        }
        if (totals.isEmpty()) {
            return List.of();
        }

        Instant now = Instant.now();
        Map<UUID, Device> resolved = devices.findAllById(totals.keySet()).stream()
                .collect(java.util.stream.Collectors.toMap(Device::getId, device -> device));

        return totals.entrySet().stream()
                .map(entry -> {
                    Device device = resolved.get(entry.getKey());
                    long[] counters = entry.getValue();
                    return ReportingDeviceDto.of(
                            entry.getKey(),
                            device == null ? null : device.getDeviceCode(),
                            device == null ? null : device.getName(),
                            device == null ? null : device.getTransport(),
                            device == null ? null : device.getSource(),
                            device == null ? null : device.getStatus(),
                            lastSeen.get(entry.getKey()),
                            counters[0], counters[1], counters[2], counters[3], now);
                })
                // Quietest first: this list is read to find what has stopped talking, so the
                // devices that matter should not be on page three.
                .sorted(Comparator.comparing(ReportingDeviceDto::lastPacketAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
    }

    private Map<Instant, Map<String, Double>> readingsFor(UUID deviceId, List<PacketLog> page) {
        Instant windowStart = null;
        Instant windowEnd = null;
        for (PacketLog log : page) {
            Instant observedAt = log.getObservedAt();
            if (observedAt == null) {
                continue;
            }
            if (windowStart == null || observedAt.isBefore(windowStart)) {
                windowStart = observedAt;
            }
            if (windowEnd == null || observedAt.isAfter(windowEnd)) {
                windowEnd = observedAt;
            }
        }
        if (windowStart == null) {
            return Map.of();
        }

        Map<Instant, Map<String, Double>> grouped = new LinkedHashMap<>();
        for (DeviceReading reading : readings.findAllInWindow(deviceId, windowStart, windowEnd)) {
            grouped.computeIfAbsent(reading.getObservedAt(), key -> new LinkedHashMap<>())
                    .put(reading.getMetric(), reading.getValue());
        }
        return grouped;
    }

    private Map<UUID, String> deviceCodes(List<PacketLog> page) {
        List<UUID> ids = page.stream()
                .map(PacketLog::getDeviceId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> codes = new HashMap<>();
        devices.findAllById(ids).forEach(device -> codes.put(device.getId(), device.getDeviceCode()));
        return codes;
    }
}
