package com.aquagrid.platform.iot.dataconfig.web.controller;

import com.aquagrid.platform.common.web.ApiPaths;
import com.aquagrid.platform.common.web.PageResponse;
import com.aquagrid.platform.iot.dataconfig.application.service.ParameterDiscoveryService;
import com.aquagrid.platform.iot.dataconfig.application.service.RawTelemetryService;
import com.aquagrid.platform.iot.dataconfig.domain.model.DiscoveryStatus;
import com.aquagrid.platform.iot.dataconfig.web.dto.DataConfigDtos.DiscoveredParameterDto;
import com.aquagrid.platform.iot.dataconfig.web.dto.DataConfigDtos.RawTelemetryDto;
import com.aquagrid.platform.iot.dataconfig.web.dto.DataConfigDtos.ReasonRequest;
import com.aquagrid.platform.security.core.AuthenticatedPrincipal;
import com.aquagrid.platform.security.core.Permissions;
import com.aquagrid.platform.security.core.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Discovered parameters, and the raw payloads behind them.
 *
 * <p>The screen that makes "accept everything" actionable rather than merely tolerant. Storing an
 * unknown field means nothing is lost; it does not mean anyone finds out. An unconfigured parameter
 * is invisible on every dashboard, absent from every report and outside every alarm rule —
 * indistinguishable, from the operator's chair, from a field the device never sent. These endpoints
 * are the difference.
 *
 * <p>Every route is under {@code iot:data-config:read}, except the ones that decide something. There
 * is deliberately no separate discovery permission: the list is the input to configuration and
 * nothing else, and a third permission would only create a state where someone can see that a
 * device is sending an unknown field and can do nothing about it.
 */
@Tag(name = "Discovered parameters",
        description = "What devices are sending that nothing describes yet, and the payloads it came in")
@RestController
@RequestMapping(value = ApiPaths.DEVICE_DATA_CONFIG, produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class DiscoveredParameterController {

    private final ParameterDiscoveryService discoveryService;
    private final RawTelemetryService rawTelemetryService;

    @GetMapping("/discovered")
    @PreAuthorize("hasAuthority('" + Permissions.DATA_CONFIG_READ + "')")
    @Operation(summary = "Parameters devices have sent that the catalogue does not describe",
            description = """
                    One row per device and parameter, with a recent sample, a guessed type and how
                    often it has arrived — not one row per packet, which for a five-minute reporting
                    interval would be a hundred thousand identical rows a year to convey one fact.

                    `PENDING` is the queue. `IGNORED` rows are still being received and stored; they
                    are simply no longer being asked about.""")
    public PageResponse<DiscoveredParameterDto> discovered(
            @RequestParam(required = false) UUID deviceId,
            @RequestParam(required = false) String deviceType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @PageableDefault(size = 25, sort = "lastSeenAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return PageResponse.of(discoveryService.search(
                        SecurityUtils.requirePrincipal().organizationId(),
                        deviceId, deviceType, DiscoveryStatus.from(status), search, pageable)
                .map(DiscoveredParameterDto::from));
    }

    @GetMapping("/discovered/pending-count")
    @PreAuthorize("hasAuthority('" + Permissions.DATA_CONFIG_READ + "')")
    @Operation(summary = "How many parameters are waiting for a decision",
            description = "Drives the badge on the menu. A queue with no visible count is a queue "
                    + "nobody opens.")
    public PendingCount pendingCount() {
        return new PendingCount(
                discoveryService.pendingCount(SecurityUtils.requirePrincipal().organizationId()));
    }

    /** The badge's number. A record rather than a bare long so the response stays extensible. */
    public record PendingCount(long pending) {
    }

    @GetMapping("/discovered/{discoveryId}/samples")
    @PreAuthorize("hasAuthority('" + Permissions.DATA_CONFIG_READ + "')")
    @Operation(summary = "Recent payloads that actually carried this parameter",
            description = """
                    The View Raw Data action, and the reason a discovery row keeps only one sample.
                    An administrator deciding what `motor_temperature` is wants a spread of values,
                    not the single reading that happened to be recorded last — and the values are
                    already there in the raw payload archive, indexed for exactly this query.""")
    public List<RawTelemetryDto> samples(@PathVariable UUID discoveryId,
                                         @RequestParam(defaultValue = "10") int limit) {
        return discoveryService
                .samplePayloads(SecurityUtils.requirePrincipal().organizationId(), discoveryId, limit)
                .stream().map(RawTelemetryDto::from).toList();
    }

    @PostMapping("/discovered/{discoveryId}/ignore")
    @PreAuthorize("hasAuthority('" + Permissions.DATA_CONFIG_MANAGE + "')")
    @Operation(summary = "Dismiss a discovered parameter from the queue",
            description = """
                    **Deletes nothing.** The payloads stay, the occurrence counter keeps climbing on
                    every further sighting, and the parameter can be configured years later with its
                    whole history intact.

                    What changes is attention: a vendor's `fw_build` field, which nobody will ever
                    chart, stops competing with the `motor_temperature` somebody should look at.""")
    public DiscoveredParameterDto ignore(@PathVariable UUID discoveryId,
                                         @RequestBody(required = false) ReasonRequest request) {
        AuthenticatedPrincipal principal = SecurityUtils.requirePrincipal();
        return DiscoveredParameterDto.from(discoveryService.ignore(discoveryId,
                principal.organizationId(), principal.userId(), principal.username(),
                request == null ? null : request.reason()));
    }

    @PostMapping("/discovered/{discoveryId}/restore")
    @PreAuthorize("hasAuthority('" + Permissions.DATA_CONFIG_MANAGE + "')")
    @Operation(summary = "Put an ignored parameter back on the queue")
    public DiscoveredParameterDto restore(@PathVariable UUID discoveryId) {
        return DiscoveredParameterDto.from(discoveryService.restore(discoveryId,
                SecurityUtils.requirePrincipal().organizationId()));
    }

    // ---- Raw payloads --------------------------------------------------------------------------

    @GetMapping("/raw-telemetry")
    @PreAuthorize("hasAuthority('" + Permissions.DATA_CONFIG_READ + "')")
    @Operation(summary = "Stored payloads, exactly as the devices sent them",
            description = """
                    Every packet, accepted or not, with its complete original payload. Never
                    modified — no normalisation, no canonicalising of keys, no dropping of fields
                    the platform had no use for.

                    Rejected packets are here too, and are usually the interesting ones: a payload
                    refused because its device is not registered is the payload somebody most needs
                    to read.""")
    public PageResponse<RawTelemetryDto> rawTelemetry(
            @RequestParam(required = false) UUID deviceId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @PageableDefault(size = 25) Pageable pageable) {
        return PageResponse.of(rawTelemetryService.search(
                        SecurityUtils.requirePrincipal().organizationId(),
                        deviceId, status, from, to, pageable)
                .map(RawTelemetryDto::from));
    }

    @GetMapping("/raw-telemetry/{payloadId}")
    @PreAuthorize("hasAuthority('" + Permissions.DATA_CONFIG_READ + "')")
    @Operation(summary = "One stored payload")
    public RawTelemetryDto rawPayload(@PathVariable UUID payloadId) {
        return RawTelemetryDto.from(rawTelemetryService.require(payloadId,
                SecurityUtils.requirePrincipal().organizationId()));
    }
}
