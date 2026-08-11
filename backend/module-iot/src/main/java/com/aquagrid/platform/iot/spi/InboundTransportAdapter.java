package com.aquagrid.platform.iot.spi;

/**
 * The hexagonal ingestion port.
 *
 * <p>Each transport (LoRaWAN, NB-IoT, MQTT, HTTP, simulator, future LTE-M) implements this SPI.
 * Adapters are activated by Spring profile/property ({@code aquagrid.iot.transports.lorawan.enabled}),
 * so a municipality running only NB-IoT never loads an MQTT client. An adapter that is not enabled
 * contributes nothing to the application context and its listener never starts.
 *
 * <p>The adapter does two things: it <em>receives</em> transport-specific bytes from its wire
 * (MQTT topic, UDP datagram, HTTP webhook), and it <em>decodes</em> them into a canonical
 * {@link com.aquagrid.platform.iot.api.DeviceMessage} via a vendor codec. The decoded message is
 * then handed to {@link com.aquagrid.platform.iot.spi.TelemetryIngestPort}, which is where business
 * logic begins — and which never knows the transport exists.
 *
 * <p>This is the seam that makes the platform transport-agnostic by construction, not by convention.
 */
public interface InboundTransportAdapter {

    /** The transport identifier this adapter handles, e.g. {@code LORAWAN}. */
    String transport();

    /** Human-readable name for logs and the transport-status endpoint. */
    default String displayName() {
        return transport();
    }

    /**
     * Starts listening on the transport's wire. Called once at startup if the adapter is enabled.
     * Implementations subscribe to MQTT topics, open UDP sockets, or register webhook routes.
     */
    void start();

    /**
     * Stops listening. Called once at shutdown. Must be idempotent.
     */
    void stop();
}
