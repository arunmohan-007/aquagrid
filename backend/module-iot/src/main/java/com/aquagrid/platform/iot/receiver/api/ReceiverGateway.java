package com.aquagrid.platform.iot.receiver.api;

import com.aquagrid.platform.iot.receiver.domain.model.InboundPacket;

/**
 * The single entry point for every inbound telemetry packet, whatever carried it.
 *
 * <p>This is the module's published contract and the invariant the receiver exists to enforce:
 * <b>no transport reaches {@code TelemetryIngestService} except through here</b>. A transport's job
 * ends at producing an {@link InboundPacket}; authentication, tenant and device resolution,
 * validation, decoding, event construction, logging, metrics and dispatch all happen behind this
 * one call, once, for everyone.
 *
 * <p>The consequence is what makes the guarantee worth having. A control that lives in the pipeline
 * — replay rejection, IP allow-listing, the packet log an audit asks for — is automatically true of
 * LoRaWAN, of MQTT and of a technology added in 2028, because none of them has its own path to the
 * database. A transport that bypassed this interface would be a silent hole in every one of those
 * controls, which is why {@code TelemetryIngestPort} is not exported beyond the module.
 *
 * <p>Implementations must be thread-safe: TCP, UDP and WebSocket receivers call this concurrently
 * from per-connection virtual threads.
 */
public interface ReceiverGateway {

    /**
     * Receives one packet, end to end.
     *
     * <p>Never throws for a packet-level problem. A malformed, unauthenticated or unattributable
     * packet is normal operation on a public ingestion endpoint — the outcome describes it, and the
     * caller decides how to acknowledge its wire. Exceptions escape only for genuine faults, and
     * those are turned into a {@link ReceptionOutcome.Rejected} with
     * {@link com.aquagrid.platform.common.error.ErrorCode#INTERNAL_ERROR} before they reach a
     * transport.
     */
    ReceptionOutcome receive(InboundPacket packet);
}
