package com.aquagrid.platform.iot.web.controller;

import com.aquagrid.platform.common.web.ApiPaths;
import com.aquagrid.platform.iot.application.service.DeviceTelemetryService;
import com.aquagrid.platform.iot.domain.model.MetricCatalog;
import com.aquagrid.platform.iot.web.dto.TelemetryDtos.DeviceTelemetryDto;
import com.aquagrid.platform.iot.web.dto.TelemetryDtos.MetricDefinitionDto;
import com.aquagrid.platform.iot.web.dto.TelemetryDtos.MetricSeriesDto;
import com.aquagrid.platform.security.core.Permissions;
import com.aquagrid.platform.security.core.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Device telemetry — what each meter is reading.
 *
 * <p>Gated on {@code iot:device:read}, whose catalogue description is already "View devices and
 * telemetry": this is the device register's own data seen from the other side, not the ingestion
 * pipeline's internals. That distinction is why it is not {@code iot:receiver:read} — an operator
 * who should see what a meter reads is not thereby entitled to see every gateway's packet payloads,
 * source addresses and authentication outcomes.
 */
@Tag(name = "Device telemetry", description = "Readings and device information, by device")
@RestController
@RequestMapping(value = ApiPaths.API_V1, produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class DeviceTelemetryController {

    /** Bounds a series request. Beyond this the right tool is an export, not a chart. */
    private static final int MAX_WINDOW_DAYS = 365;

    private final DeviceTelemetryService telemetryService;

    @GetMapping("/devices/{deviceId}/telemetry")
    @PreAuthorize("hasAuthority('" + Permissions.DEVICE_READ + "')")
    @Operation(summary = "A device's information and its latest reading of every metric",
            description = """
                    The device's identity, siting and network state, plus the most recent value of
                    each metric it has reported, grouped by what the metric describes — meter
                    reading, pressure, device health, conditions.

                    Every value carries its age. A number with no age is unreadable: 3.1 V from an
                    hour ago and 3.1 V from last March are the same number and different facts.

                    Metrics the platform has no catalogue entry for are still returned, under
                    "Other readings" — the device measured them and the platform stored them, so
                    hiding them here would lose data that exists.""")
    public DeviceTelemetryDto telemetry(@PathVariable UUID deviceId) {
        return telemetryService.telemetryFor(tenant(), deviceId);
    }

    @GetMapping("/devices/{deviceId}/telemetry/series")
    @PreAuthorize("hasAuthority('" + Permissions.DEVICE_READ + "')")
    @Operation(summary = "One metric's history",
            description = """
                    Oldest first, which is the order a chart wants. Bounded; the response says
                    whether it was truncated, so a trend is never silently drawn from a tail.

                    A cumulative register plots as a ramp — its *difference* between two points is
                    the consumption. The `kind` on each metric says which it is.""")
    public MetricSeriesDto series(@PathVariable UUID deviceId,
                                  @RequestParam String metric,
                                  @RequestParam(required = false) Instant from,
                                  @RequestParam(required = false) Instant to,
                                  @RequestParam(defaultValue = "24") int hours) {
        Instant end = to == null ? Instant.now() : to;
        Instant start = from != null
                ? from
                : end.minus(Duration.ofHours(Math.clamp(hours, 1, MAX_WINDOW_DAYS * 24)));
        return telemetryService.seriesFor(tenant(), deviceId, metric, start, end);
    }

    @GetMapping("/telemetry/metric-catalog")
    @PreAuthorize("hasAuthority('" + Permissions.DEVICE_READ + "')")
    @Operation(summary = "Every metric the platform understands",
            description = """
                    Label, unit, kind and category for each canonical metric, served from the same
                    declaration the ingest path stamps units from. The client keeps no metric table
                    of its own, so a metric added server-side appears in the UI without a frontend
                    change — the same arrangement the device registration form uses for its
                    communication fields.""")
    public List<MetricDefinitionDto> metricCatalog() {
        return MetricCatalog.all().stream().map(MetricDefinitionDto::from).toList();
    }

    /** The tenant of the caller — from the principal, never a request parameter. */
    private static UUID tenant() {
        return SecurityUtils.currentOrganizationId()
                .orElseThrow(() -> new IllegalStateException("No tenant bound to the request"));
    }
}
