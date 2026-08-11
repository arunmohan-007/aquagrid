package com.aquagrid.platform.iot.receiver.spi;

import com.aquagrid.platform.iot.spi.InboundTransportAdapter;

/**
 * The seam a communication technology plugs into.
 *
 * <p>Extends the platform's existing {@link InboundTransportAdapter} rather than replacing it: that
 * interface already defines what every transport must do about its own lifecycle — name itself,
 * start listening, stop idempotently — and a second, parallel lifecycle contract would only create
 * two places to register a transport and one of them to forget.
 *
 * <p>What a receiver adds is the part the pipeline needs: a declaration of how it is reached, and a
 * live account of itself for the status endpoint. It does <em>not</em> declare a {@code receive}
 * method, and that omission is deliberate. Transports are triggered in irreconcilable ways — an
 * MVC handler, a broker callback, a blocking socket read, a WebSocket frame — and forcing them
 * through one signature would mean adapting every one of them to a shape that fits none. What they
 * share is the {@link com.aquagrid.platform.iot.receiver.api.ReceiverGateway} they hand their
 * packet to, and {@code AbstractTransportReceiver} supplies that.
 *
 * <p><b>Adding a technology</b> — Kafka, AMQP, OPC-UA — is: implement this, build an
 * {@link com.aquagrid.platform.iot.receiver.domain.model.InboundPacket} from whatever arrives, call
 * the gateway. No existing class changes. See {@code docs/} for the worked example.
 */
public interface TransportReceiver extends InboundTransportAdapter {

    /**
     * Where this receiver listens, for the status endpoint and the startup log — a route, a topic
     * filter, a {@code host:port}. Never credentials.
     */
    String endpointDescription();

    /**
     * Whether the transport holds connections open between packets.
     *
     * <p>Stateful transports (TCP, MQTT, WebSocket) get a communication session and a connection
     * history; stateless ones (HTTP, UDP, LoRaWAN webhooks) would produce one session row per
     * packet, which is the packet log under another name. The receiver uses this to decide, so no
     * stage has to know which transports are which.
     */
    default boolean stateful() {
        return false;
    }

    /** Live self-report: running, listening where, how many connections. */
    TransportStatus status();
}
