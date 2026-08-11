package com.aquagrid.platform.iot.receiver.web.controller;

import com.aquagrid.platform.common.web.PageResponse;
import com.aquagrid.platform.iot.receiver.application.service.ReceiverQueryService;
import com.aquagrid.platform.iot.receiver.infrastructure.config.ReceiverModuleConfig;
import com.aquagrid.platform.iot.receiver.infrastructure.transport.TransportReceiverRegistry;
import com.aquagrid.platform.iot.receiver.spi.TransportStatus;
import com.aquagrid.platform.iot.receiver.web.dto.ReceiverPacketDto;
import com.aquagrid.platform.iot.receiver.web.dto.ReportingDeviceDto;
import com.aquagrid.platform.security.core.Permissions;
import com.aquagrid.platform.security.core.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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
 * The receiver's operator API: what each device sent, and when.
 *
 * <p>Shares the {@code /receiver} prefix with the ingress endpoint but not its security posture.
 * Ingress is method-scoped public because gateways cannot hold a user session; every route here
 * requires a JWT and an explicit {@code @PreAuthorize}. That is why {@code ReceiverPublicEndpoints}
 * exposes {@code POST /receiver/*} rather than {@code /receiver/**} — the wildcard that would have
 * been easier to write would also have published this controller to the internet.
 *
 * <p>The tenant comes from the authenticated principal on every call and is passed explicitly into
 * every query. {@code receiver_packet_logs} cannot carry the Hibernate tenant filter — it holds
 * rows belonging to no tenant by design — so this is the only thing standing between one utility's
 * operator and another's traffic.
 */
@Tag(name = "Receiver", description = "Packet history, device reporting and transport status")
@RestController
@RequestMapping(value = ReceiverModuleConfig.INGRESS_BASE, produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class ReceiverAdminController {

    /** Bounded so a page size of 100,000 cannot be used to pull the packet log into memory. */
    private static final int MAX_PAGE_SIZE = 200;

    private final ReceiverQueryService queryService;
    private final TransportReceiverRegistry registry;

    @GetMapping("/devices")
    @PreAuthorize("hasAuthority('" + Permissions.RECEIVER_READ + "')")
    @Operation(summary = "Which devices are reporting, and when each last did",
            description = """
                    Every device that has sent a packet in the window, quietest first — the order
                    that puts a device which has stopped reporting at the top rather than on page
                    three. Read from the hourly rollups, so the window can be long without scanning
                    the packet log.""")
    public List<ReportingDeviceDto> reportingDevices(
            @RequestParam(defaultValue = "24") int hours) {
        Instant since = Instant.now().minus(Duration.ofHours(Math.clamp(hours, 1, 24 * 90)));
        return queryService.reportingDevices(tenant(), since);
    }

    @GetMapping("/devices/{deviceId}/packets")
    @PreAuthorize("hasAuthority('" + Permissions.RECEIVER_READ + "')")
    @Operation(summary = "What one device sent, with timestamps and values",
            description = """
                    Newest first. Each entry carries both timestamps — receivedAt (our clock) and
                    observedAt (the device's) — plus the gap between them, which is the number that
                    distinguishes a healthy link from a device that buffered through an outage from
                    one whose clock is wrong.

                    The metric values recorded from each packet are included. Rejected packets
                    appear here too, with the reason: a device that is transmitting but being
                    refused looks identical to a silent one in any view that only shows successes.""")
    public PageResponse<ReceiverPacketDto> deviceFeed(
            @PathVariable UUID deviceId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return queryService.deviceFeed(tenant(), deviceId,
                from == null ? Instant.now().minus(Duration.ofDays(7)) : from,
                to == null ? Instant.now() : to,
                pageable(page, size));
    }

    @GetMapping("/packets")
    @PreAuthorize("hasAuthority('" + Permissions.RECEIVER_READ + "')")
    @Operation(summary = "Search packets across devices",
            description = """
                    Every filter is optional. Values are not included — that would cost a query per
                    device on the page; use the per-device feed when the readings are what matters.""")
    public PageResponse<ReceiverPacketDto> searchPackets(
            @RequestParam(required = false) UUID deviceId,
            @RequestParam(required = false) String transport,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String errorCode,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return queryService.search(tenant(), deviceId, transport, status, errorCode,
                from, to, pageable(page, size));
    }

    @GetMapping("/transports")
    @PreAuthorize("hasAuthority('" + Permissions.RECEIVER_READ + "')")
    @Operation(summary = "Transport listener status",
            description = """
                    Reported by each listener about itself. A listener that is running but has seen
                    nothing for an hour is indistinguishable, in the database, from one that never
                    started — and only the transport can tell those apart.""")
    public List<TransportStatus> transports() {
        return registry.statuses();
    }

    /**
     * The tenant of the caller.
     *
     * <p>From the principal, never from a request parameter — the platform's tenancy invariant. A
     * {@code ?organizationId=} on this endpoint would be a cross-tenant read primitive.
     */
    private static UUID tenant() {
        return SecurityUtils.currentOrganizationId()
                .orElseThrow(() -> new IllegalStateException("No tenant bound to the request"));
    }

    private static Pageable pageable(int page, int size) {
        return PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE));
    }
}
