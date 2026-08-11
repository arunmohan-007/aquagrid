package com.aquagrid.platform.iot.web.dto;

import com.aquagrid.platform.iot.domain.model.MetricCatalog;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The device-telemetry API contract (Module 7).
 *
 * <p>Everything the client needs to render a reading is on the reading: its label, its unit, how to
 * read it and which group it belongs to all come from {@link MetricCatalog} on the server. The
 * client has no metric table of its own, so a metric added server-side appears in the UI without a
 * frontend change — the same server-driven arrangement the device registration form uses for its
 * communication fields.
 */
public final class TelemetryDtos {

    private TelemetryDtos() {
    }

    /**
     * One metric's current value.
     *
     * @param metric      canonical metric name
     * @param label       what the operator reads
     * @param unit        canonical unit; not rendered for a {@code FLAG}
     * @param kind        {@code MEASUREMENT}, {@code COUNTER} or {@code FLAG} — decides how the
     *                    value should be displayed and whether plotting it means anything
     * @param category    the group it belongs to
     * @param value       the reading
     * @param observedAt  the device's own clock at the moment it measured
     * @param ageSeconds  how old the reading is. Present on every value because a number with no
     *                    age is unreadable: 3.1 V from an hour ago and 3.1 V from last March are
     *                    the same number and completely different facts
     */
    @Schema(name = "MetricReading", description = "The latest value of one metric")
    public record MetricReadingDto(
            String metric,
            String label,
            String unit,
            MetricCatalog.Kind kind,
            MetricCatalog.Category category,
            Double value,
            Instant observedAt,
            long ageSeconds
    ) {
    }

    /**
     * A group of readings, as an operator reads them.
     *
     * @param category     the group
     * @param label        its display name
     * @param readings     the metrics in it, in catalogue order
     */
    @Schema(name = "MetricCategoryGroup", description = "Readings grouped by what they describe")
    public record MetricGroupDto(
            MetricCatalog.Category category,
            String label,
            List<MetricReadingDto> readings
    ) {
    }

    /**
     * A device and everything currently known about it.
     *
     * <p>Identity and siting beside the live values deliberately. The question this screen answers
     * is "what is this meter doing", and answering it needs both — a flow rate means one thing at a
     * household connection and another at a reservoir outlet, and an operator should not have to
     * hold the device register open in a second tab to know which they are looking at.
     *
     * @param lastSeenAt   last uplink, from the device row — refreshed by ingestion on every packet
     * @param silentForSeconds how long since. The number that says whether anything below is current
     * @param groups       readings, grouped and ordered
     * @param reportingMetrics every metric the device has reported in the window, for the series
     *                     selector — including ones with no catalogue entry
     */
    @Schema(name = "DeviceTelemetry", description = "A device's identity, state and latest readings")
    public record DeviceTelemetryDto(
            UUID deviceId,
            String deviceCode,
            String name,
            String deviceType,
            String transport,
            String deviceSource,
            String status,
            String networkAddress,
            String serialNumber,
            String manufacturer,
            String model,
            String firmwareVersion,
            String assetNumber,
            Double latitude,
            Double longitude,
            java.time.LocalDate installationDate,
            Instant lastSeenAt,
            Long silentForSeconds,
            Double batteryV,
            Double rssi,
            Double snr,
            List<MetricGroupDto> groups,
            List<String> reportingMetrics
    ) {
    }

    /**
     * One metric's history.
     *
     * @param points     oldest first, which is the order a chart wants and the reverse of the order
     *                   the packet feed wants. Stated rather than left to the caller to discover
     * @param truncated  true when the window held more points than the cap and the response is a
     *                   tail rather than the whole window. A chart drawn from a silently truncated
     *                   series shows a trend that starts where the limit did, not where the data did
     */
    @Schema(name = "MetricSeries", description = "One metric's readings over a window")
    public record MetricSeriesDto(
            String metric,
            String label,
            String unit,
            MetricCatalog.Kind kind,
            Instant from,
            Instant to,
            boolean truncated,
            List<SeriesPointDto> points
    ) {
    }

    @Schema(name = "SeriesPoint")
    public record SeriesPointDto(Instant observedAt, Double value) {
    }

    /** The metric catalogue, so the client never needs a table of its own. */
    @Schema(name = "MetricDefinition")
    public record MetricDefinitionDto(
            String metric,
            String label,
            String unit,
            MetricCatalog.Kind kind,
            MetricCatalog.Category category,
            String categoryLabel
    ) {
        public static MetricDefinitionDto from(MetricCatalog.Definition definition) {
            return new MetricDefinitionDto(definition.metric(), definition.label(),
                    definition.unit(), definition.kind(), definition.category(),
                    definition.category().label());
        }
    }
}
