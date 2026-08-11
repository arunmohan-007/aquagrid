package com.aquagrid.platform.iot.web.controller;

import com.aquagrid.platform.common.web.ApiPaths;
import com.aquagrid.platform.iot.application.service.ReadingExportService;
import com.aquagrid.platform.security.core.Permissions;
import com.aquagrid.platform.security.core.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Report downloads — timestamped readings as a spreadsheet or a PDF.
 *
 * <p>Gated on {@code report:report:generate} rather than {@code iot:device:read}. Reading one
 * device's telemetry on a screen and extracting the whole fleet's history to a file that leaves the
 * platform are different acts with different blast radii, and the permission catalogue already
 * separates them — the reporting role is held by analysts who are not device administrators.
 *
 * <p>Responses stream. A {@link StreamingResponseBody} hands the writer the servlet's own output
 * stream, so a 200,000-row spreadsheet is never assembled in memory to be measured for a
 * Content-Length header — which is also why no length is set.
 */
@Slf4j
@Tag(name = "Reports", description = "Download device readings as Excel or PDF")
@RestController
@RequestMapping(ApiPaths.API_V1 + "/reports")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class ReadingExportController {

    private final ReadingExportService exportService;

    @GetMapping("/readings")
    @PreAuthorize("hasAuthority('" + Permissions.REPORT_GENERATE + "')")
    @Operation(summary = "Download readings, timestamped",
            description = """
                    One row per reading, in time order, with the device that produced it named on
                    every row.

                    Three filters, and they are independent axes rather than alternatives:
                    `deviceId` for a single instrument, `deviceType` for a class of them (water
                    meter, pressure sensor, quality sensor) and `transport` for a network (LoRaWAN,
                    NB-IoT, cellular). "Every pressure sensor" and "everything on LoRaWAN" are
                    different questions and a report may ask both at once.

                    Both formats come from one query, so a spreadsheet and a PDF of the same request
                    cannot disagree. Each is capped and says so in the file when the cap is reached
                    — a report that silently stopped at a round number is one somebody would
                    reconcile against.""")
    public ResponseEntity<StreamingResponseBody> readings(
            @RequestParam(defaultValue = "XLSX") String format,
            @RequestParam(required = false) UUID deviceId,
            @RequestParam(required = false) String deviceType,
            @RequestParam(required = false) String transport,
            @RequestParam(required = false) String metric,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {

        Instant end = to == null ? Instant.now() : to;
        Instant start = from == null ? end.minus(Duration.ofDays(7)) : from;

        ReadingExportService.Format chosen = ReadingExportService.Format.from(format);
        ReadingExportService.Request request = new ReadingExportService.Request(
                deviceId, blankToNull(deviceType), blankToNull(transport), blankToNull(metric),
                start, end, chosen);

        UUID organizationId = tenant();
        String generatedBy = SecurityUtils.currentPrincipal()
                .map(com.aquagrid.platform.security.core.AuthenticatedPrincipal::username)
                .orElse(null);
        String filename = exportService.filenameFor(request);

        StreamingResponseBody body = out ->
                exportService.export(organizationId, null, generatedBy, request, out);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(chosen.contentType()))
                // `attachment` with an explicit filename: without it a browser renders the PDF
                // in a tab named after the endpoint, and the spreadsheet downloads as "readings".
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename).build().toString())
                .body(body);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** The tenant of the caller — from the principal, never a request parameter. */
    private static UUID tenant() {
        return SecurityUtils.currentOrganizationId()
                .orElseThrow(() -> new IllegalStateException("No tenant bound to the request"));
    }
}
