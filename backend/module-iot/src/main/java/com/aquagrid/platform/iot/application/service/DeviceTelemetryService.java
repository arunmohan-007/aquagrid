package com.aquagrid.platform.iot.application.service;

import com.aquagrid.platform.common.error.ResourceNotFoundException;
import com.aquagrid.platform.iot.domain.model.Device;
import com.aquagrid.platform.iot.domain.model.DeviceReading;
import com.aquagrid.platform.iot.domain.model.MetricCatalog;
import com.aquagrid.platform.iot.infrastructure.persistence.DeviceReadingRepository;
import com.aquagrid.platform.iot.infrastructure.persistence.DeviceRepository;
import com.aquagrid.platform.iot.web.dto.TelemetryDtos.DeviceTelemetryDto;
import com.aquagrid.platform.iot.web.dto.TelemetryDtos.MetricGroupDto;
import com.aquagrid.platform.iot.web.dto.TelemetryDtos.MetricReadingDto;
import com.aquagrid.platform.iot.web.dto.TelemetryDtos.MetricSeriesDto;
import com.aquagrid.platform.iot.web.dto.TelemetryDtos.SeriesPointDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What one device is reporting, and what it is.
 *
 * <p>The device registry answers "what is registered" and the receiver answers "what arrived on the
 * wire". Neither answers the question an operator actually opens the platform with — <em>what is
 * this meter reading</em> — because the registry holds no telemetry and the receiver's per-device
 * feed is organised by packet. A packet is the right unit for diagnosing ingestion and the wrong one
 * for reading a meter: the reading an operator wants is the latest value of each metric, grouped by
 * what it describes, whichever packets happened to carry them.
 *
 * <p>Every method takes the tenant explicitly and filters on it. A device belonging to another
 * organisation is treated as absent rather than forbidden — distinguishing the two would make this
 * endpoint a way to test which device ids exist.
 */
@Service
@RequiredArgsConstructor
public class DeviceTelemetryService {

    /**
     * How far back a value may come from and still count as the device's current state.
     *
     * <p>Bounds the summary query, and more importantly bounds the claim it makes. A metric last
     * reported months ago is not part of what the device is doing now, and showing it beside live
     * values would be presenting history as present tense. Readings inside the window carry their
     * own age so the reader can judge, but something has to be the outer edge.
     */
    private static final Duration CURRENT_STATE_WINDOW = Duration.ofDays(30);

    /**
     * Points returned for one series.
     *
     * <p>A cap rather than a page, because a chart is not paged: the caller wants the shape of the
     * window and asking them to stitch pages together to get it would push the aggregation into the
     * browser. Two thousand points is beyond the resolution of any screen it will be drawn on, and
     * the response says when it truncated so a chart is never silently drawn from a tail.
     */
    private static final int MAX_SERIES_POINTS = 2_000;

    private final DeviceRepository devices;
    private final DeviceReadingRepository readings;

    /** A device's identity, state and latest reading of every metric, grouped. */
    @Transactional(readOnly = true)
    public DeviceTelemetryDto telemetryFor(UUID organizationId, UUID deviceId) {
        Device device = require(organizationId, deviceId);
        Instant now = Instant.now();
        Instant since = now.minus(CURRENT_STATE_WINDOW);

        List<MetricReadingDto> latest = readings.findLatestPerMetric(deviceId, since).stream()
                .map(reading -> toReading(reading, now))
                .toList();

        return new DeviceTelemetryDto(
                device.getId(),
                device.getDeviceCode(),
                device.getName(),
                device.getDeviceType(),
                device.getTransport(),
                device.getSource(),
                device.getStatus(),
                device.getNetworkAddress(),
                device.getSerialNumber(),
                device.getManufacturer(),
                device.getModel(),
                device.getFirmwareVersion(),
                device.getAssetNumber(),
                device.getLocation() == null ? null : device.getLocation().getY(),
                device.getLocation() == null ? null : device.getLocation().getX(),
                device.getInstallationDate(),
                device.getLastSeenAt(),
                device.getLastSeenAt() == null
                        ? null : Duration.between(device.getLastSeenAt(), now).toSeconds(),
                toDouble(device.getBatteryV()),
                toDouble(device.getRssi()),
                toDouble(device.getSnr()),
                group(latest),
                readings.findMetricsReported(deviceId, since));
    }

    /**
     * One metric's history over a window.
     *
     * <p>Returned oldest-first, the reverse of the repository's order. The packet feed reads newest
     * first because an operator scans it for what just happened; a series is read left to right
     * because it is drawn. Reversing here rather than in the client keeps the two callers of the
     * same underlying query from each having an opinion about ordering.
     */
    @Transactional(readOnly = true)
    public MetricSeriesDto seriesFor(UUID organizationId, UUID deviceId, String metric,
                                     Instant from, Instant to) {
        require(organizationId, deviceId);
        MetricCatalog.Definition definition = MetricCatalog.of(metric);

        List<DeviceReading> found = readings.findSeries(deviceId, metric, from, to);
        boolean truncated = found.size() > MAX_SERIES_POINTS;

        // findSeries returns newest first, so the cap keeps the most recent points — the tail a
        // reader cares about — rather than the oldest, which is what taking from the front would do.
        List<DeviceReading> capped = truncated ? found.subList(0, MAX_SERIES_POINTS) : found;

        List<SeriesPointDto> points = new ArrayList<>(capped.size());
        for (int i = capped.size() - 1; i >= 0; i--) {
            DeviceReading reading = capped.get(i);
            points.add(new SeriesPointDto(reading.getObservedAt(), reading.getValue()));
        }

        return new MetricSeriesDto(metric, definition.label(), definition.unit(), definition.kind(),
                from, to, truncated, points);
    }

    /**
     * Groups readings by category, in catalogue order.
     *
     * <p>An {@link EnumMap} keyed on the category, so groups come out in the enum's declaration
     * order — consumption first, because that is what the screen exists to show — rather than in
     * whatever order the metrics happened to be stored in.
     */
    private static List<MetricGroupDto> group(List<MetricReadingDto> latest) {
        Map<MetricCatalog.Category, List<MetricReadingDto>> byCategory =
                new EnumMap<>(MetricCatalog.Category.class);
        for (MetricReadingDto reading : latest) {
            byCategory.computeIfAbsent(reading.category(), key -> new ArrayList<>()).add(reading);
        }

        List<MetricGroupDto> groups = new ArrayList<>(byCategory.size());
        byCategory.forEach((category, entries) -> {
            entries.sort(Comparator.comparing(MetricReadingDto::label));
            groups.add(new MetricGroupDto(category, category.label(), List.copyOf(entries)));
        });
        return groups;
    }

    private static MetricReadingDto toReading(DeviceReading reading, Instant now) {
        MetricCatalog.Definition definition = MetricCatalog.of(reading.getMetric());
        return new MetricReadingDto(
                reading.getMetric(),
                definition.label(),
                // The stored unit wins where there is one: it is what was true when the reading was
                // taken, and a catalogue edit must not retroactively relabel historical values.
                reading.getUnit() != null ? reading.getUnit() : definition.unit(),
                definition.kind(),
                definition.category(),
                reading.getValue(),
                reading.getObservedAt(),
                Duration.between(reading.getObservedAt(), now).toSeconds());
    }

    private Device require(UUID organizationId, UUID deviceId) {
        return devices.findById(deviceId)
                .filter(device -> device.getOrganizationId().equals(organizationId))
                .orElseThrow(() -> new ResourceNotFoundException("Device", deviceId));
    }

    private static Double toDouble(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }
}
