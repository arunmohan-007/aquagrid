package com.aquagrid.platform.iot.web.controller;

import com.aquagrid.platform.common.web.ApiPaths;
import com.aquagrid.platform.iot.api.DeviceMessage;
import com.aquagrid.platform.iot.spi.IngestResult;
import com.aquagrid.platform.iot.spi.TelemetryIngestPort;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Legacy Phase-5 HTTP ingestion webhook — <b>disabled by default</b>.
 *
 * <p>The production ingress is {@code POST /api/v1/receiver/http}. That route runs the receiver
 * pipeline: authentication, IP allow-listing, rate limits, device resolution, replay protection and
 * raw-payload retention. This controller predates that pipeline and calls
 * {@link TelemetryIngestPort} directly, so it has none of those controls.
 *
 * <p>It remains in the tree only so a laboratory that already scripted against
 * {@code /api/v1/ingest/http} can opt back in with
 * {@code aquagrid.iot.legacy-http-ingest.enabled=true}. {@code ProductionReadinessVerifier} refuses
 * to boot a non-development profile with that flag set.
 *
 * <p>Even when enabled it is <em>not</em> a public endpoint: Spring Security still requires a user
 * JWT. That is the wrong credential model for meters, which is another reason not to use it.
 */
@Slf4j
@Hidden
@Tag(name = "IoT Ingestion (legacy)", description = "Disabled Phase-5 HTTP ingest — use /receiver/http")
@RestController
@RequestMapping(value = ApiPaths.API_V1 + "/ingest", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "aquagrid.iot.legacy-http-ingest", name = "enabled",
        havingValue = "true")
public class HttpIngestController {

    private final TelemetryIngestPort ingestPort;

    @PostMapping(value = "/http", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Legacy HTTP ingest (disabled by default)",
            description = """
                    Do not use for production devices. Prefer POST /api/v1/receiver/http, which
                    authenticates the packet and retains the raw payload through the receiver.""")
    @ApiResponse(responseCode = "202", description = "Ingested or duplicate")
    @ApiResponse(responseCode = "400", description = "Malformed payload")
    @ApiResponse(responseCode = "404", description = "Unknown device")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public IngestOutcome ingest(@Valid @RequestBody HttpReadingPayload payload) {
        DeviceMessage message = new DeviceMessage(
                payload.deviceEui(),
                payload.observedAt() != null ? payload.observedAt() : Instant.now(),
                Instant.now(),
                payload.metrics() != null ? payload.metrics() : Map.of(),
                payload.rssi(), payload.snr(), payload.batteryV(), payload.fCnt(),
                DeviceMessage.Transports.HTTP,
                payload.raw() != null ? payload.raw() : new HashMap<>());

        IngestResult result = ingestPort.ingest(message);
        return switch (result) {
            case IngestResult.Accepted a -> new IngestOutcome("ACCEPTED", a.deviceId(), null);
            case IngestResult.Duplicate d -> new IngestOutcome("DUPLICATE", d.deviceId(), null);
            case IngestResult.Rejected r -> {
                log.info("Legacy HTTP ingest rejected: {}", r.reason());
                yield new IngestOutcome("REJECTED", null, r.reason());
            }
        };
    }

    /** The JSON body a device POSTs. Vendor-specific fields go in {@code raw}. */
    public record HttpReadingPayload(
            @NotBlank String deviceEui,
            Instant observedAt,
            Map<String, Double> metrics,
            Double rssi,
            Double snr,
            Double batteryV,
            Integer fCnt,
            Map<String, Object> raw
    ) {
    }

    public record IngestOutcome(String status, String deviceId, String reason) {
    }
}
