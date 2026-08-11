package com.aquagrid.platform.iot.receiver.web.controller;

import com.aquagrid.platform.common.error.ErrorCode;
import com.aquagrid.platform.common.error.ProblemDetails;
import com.aquagrid.platform.iot.receiver.api.ReceptionOutcome;
import com.aquagrid.platform.iot.receiver.domain.model.InboundPacket;
import com.aquagrid.platform.iot.receiver.infrastructure.config.ReceiverModuleConfig;
import com.aquagrid.platform.iot.receiver.infrastructure.transport.HttpPacketAssembler;
import com.aquagrid.platform.iot.receiver.infrastructure.transport.HttpTransportReceiver;
import com.aquagrid.platform.iot.receiver.infrastructure.transport.TransportReceiverRegistry;
import com.aquagrid.platform.iot.receiver.spi.TransportReceiver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * The HTTP ingress. Every packet arriving over HTTP enters the platform here.
 *
 * <p>One route with the transport as a path variable, rather than a controller per technology. The
 * alternative — {@code /ingest/lorawan}, {@code /ingest/nbiot}, one class each — is what the module
 * replaces: five controllers that each decoded a body, each called the ingest port, and each had
 * their own idea about authentication, because there was nowhere shared to put it. Adding a
 * technology meant writing another one and remembering everything the others did.
 *
 * <p>The transport is validated against the registry rather than parsed into an enum, so an
 * unregistered technology is a clean {@code 501} instead of a route that exists and quietly does
 * nothing. A deployment that has not enabled LoRaWAN has no LoRaWAN receiver bean, and this
 * endpoint says so.
 *
 * <p><b>Response semantics matter to the wire.</b> Accepted and duplicate are both {@code 202}: a
 * duplicate has already been accounted for, and refusing to acknowledge it makes the gateway
 * retransmit forever. Rejections carry an RFC 7807 problem document with the platform's stable
 * error code, and a retryable rejection returns {@code 503} so a gateway holding readings knows to
 * keep them rather than discard them.
 */
@Slf4j
@Tag(name = "Receiver Ingress", description = "The single entry point for inbound telemetry")
@RestController
@RequestMapping(value = ReceiverModuleConfig.INGRESS_BASE, produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class ReceiverIngressController {

    private final TransportReceiverRegistry registry;
    private final HttpPacketAssembler assembler;

    @PostMapping(value = "/{transport}", consumes = MediaType.ALL_VALUE)
    @Operation(summary = "Deliver a telemetry packet",
            description = """
                    Accepts a packet for any enabled HTTP-delivered transport — http, lorawan,
                    nbiot, cellular, mqtt. The body is passed through untouched: the receiver
                    selects a parser by payload encoding and communication profile, not by route,
                    so a ChirpStack envelope, a carrier JSON webhook and a hex meter frame are all
                    valid here.

                    Credentials may be presented as X-API-Key, X-Device-Token, an HMAC in
                    X-Signature (with X-Nonce and X-Timestamp), or an Authorization bearer token.""")
    @ApiResponse(responseCode = "202", description = "Accepted, or recognised as already ingested")
    @ApiResponse(responseCode = "400", description = "Malformed or failed validation")
    @ApiResponse(responseCode = "401", description = "Authentication failed")
    @ApiResponse(responseCode = "404", description = "No registered device matches the packet")
    @ApiResponse(responseCode = "501", description = "That transport is not enabled")
    @ApiResponse(responseCode = "503", description = "Temporary failure — retry with the same packet")
    public ResponseEntity<?> receive(@PathVariable String transport,
                                     @RequestBody(required = false) byte[] body,
                                     HttpServletRequest request) {

        Optional<TransportReceiver> receiver = registry.find(normalise(transport));
        if (receiver.isEmpty() || !(receiver.get() instanceof HttpTransportReceiver http)) {
            // 501, not 404: the path is correct, the capability is absent. A gateway operator
            // reading a 404 goes looking for a typo in their URL.
            return problem(ErrorCode.RECEIVER_UNSUPPORTED_TRANSPORT,
                    "Transport '" + transport + "' is not enabled on this deployment",
                    request);
        }

        InboundPacket packet = assembler.assemble(http.transport(),
                body == null ? new byte[0] : body, request);
        ReceptionOutcome outcome = http.deliver(packet);

        return switch (outcome) {
            case ReceptionOutcome.Accepted accepted -> ResponseEntity.accepted()
                    .body(IngressResponse.accepted(accepted));
            case ReceptionOutcome.Duplicate duplicate -> ResponseEntity.accepted()
                    .body(IngressResponse.duplicate(duplicate));
            case ReceptionOutcome.Rejected rejected -> rejection(rejected, request);
        };
    }

    /**
     * Turns a rejection into a problem document.
     *
     * <p>Retryable failures are reported as {@code 503} rather than as the error's own status. The
     * distinction is what a gateway acts on: a 400 tells it the packet is bad and it should drop
     * it, which for a transient database failure would destroy a reading that was never at fault.
     */
    private ResponseEntity<ProblemDetail> rejection(ReceptionOutcome.Rejected rejected,
                                                    HttpServletRequest request) {
        HttpStatus status = rejected.retryable()
                ? HttpStatus.SERVICE_UNAVAILABLE
                : rejected.code().getStatus();

        ProblemDetail problem = ProblemDetails.of(status, rejected.code(),
                rejected.detail(), request.getRequestURI());
        // Quoted back so an operator can find the packet-log row from the gateway's own error log,
        // without needing anything else about the request.
        problem.setProperty("packetId", rejected.packetId().toString());
        problem.setProperty("retryable", rejected.retryable());
        return ResponseEntity.status(status).body(problem);
    }

    private ResponseEntity<ProblemDetail> problem(ErrorCode code, String detail,
                                                  HttpServletRequest request) {
        return ResponseEntity.status(code.getStatus())
                .body(ProblemDetails.of(code, detail, request.getRequestURI()));
    }

    /** {@code nb-iot}, {@code nbiot} and {@code NB_IOT} all name one transport. */
    private static String normalise(String transport) {
        return transport == null ? null
                : transport.trim().toUpperCase().replace('-', '_');
    }

    /**
     * The success body.
     *
     * @param status   {@code ACCEPTED} or {@code DUPLICATE} — a gateway that distinguishes them can
     *                 stop retransmitting sooner
     * @param packetId quotable to support, and the key to the packet log
     */
    public record IngressResponse(String status, String packetId, String deviceId) {

        static IngressResponse accepted(ReceptionOutcome.Accepted outcome) {
            return new IngressResponse("ACCEPTED", outcome.packetId().toString(),
                    outcome.deviceId() == null ? null : outcome.deviceId().toString());
        }

        static IngressResponse duplicate(ReceptionOutcome.Duplicate outcome) {
            return new IngressResponse("DUPLICATE", outcome.packetId().toString(),
                    outcome.deviceId() == null ? null : outcome.deviceId().toString());
        }
    }
}
