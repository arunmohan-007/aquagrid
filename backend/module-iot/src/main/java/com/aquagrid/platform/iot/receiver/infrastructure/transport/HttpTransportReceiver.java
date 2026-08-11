package com.aquagrid.platform.iot.receiver.infrastructure.transport;

import com.aquagrid.platform.iot.receiver.api.ReceiverGateway;
import com.aquagrid.platform.iot.receiver.api.ReceptionOutcome;
import com.aquagrid.platform.iot.receiver.domain.model.InboundPacket;

/**
 * A receiver for any transport whose packets are delivered over HTTP.
 *
 * <p>One class, configured four times — HTTP, LoRaWAN, NB-IoT and Cellular — rather than four
 * classes differing only in a string. That is not a shortcut: those four genuinely share their
 * entire delivery mechanism. A ChirpStack LoRaWAN integration, a carrier NB-IoT webhook, a 4G
 * modem's REST push and a plain HTTP device all arrive as an HTTP POST on a route the servlet
 * container owns, and everything that <em>does</em> differ between them — how the payload is
 * encoded, which identifier names the device, what credential it carries — is already the job of a
 * parser, a resolution strategy and an authenticator.
 *
 * <p>Writing them as four subclasses would have produced four copies of "hand the body to the
 * gateway" and no behavioural difference at all, which is precisely the duplication the brief rules
 * out. The transports remain independently switchable because each is a separately-conditional
 * bean; see {@code ReceiverModuleConfig}.
 *
 * <p>There is no listener to start: the servlet container is already listening, and the route
 * exists because the ingress controller declares it. {@code start} and {@code stop} therefore only
 * mark state for the status endpoint — which is honest, and better than pretending to own a socket.
 */
public class HttpTransportReceiver extends AbstractTransportReceiver {

    private final String transport;
    private final String displayName;
    private final String route;

    public HttpTransportReceiver(String transport, String displayName, String route,
                                 ReceiverGateway gateway) {
        super(gateway);
        this.transport = transport;
        this.displayName = displayName;
        this.route = route;
    }

    /**
     * Delivers a packet assembled from an HTTP request.
     *
     * <p>Public where {@link AbstractTransportReceiver#accept} is protected, because for this
     * transport the caller is the ingress controller rather than the receiver's own read loop. It
     * is the single door: the controller has no reference to the gateway, so it cannot route around
     * the counters or the pipeline.
     */
    public ReceptionOutcome deliver(InboundPacket packet) {
        return accept(packet);
    }

    @Override
    public String transport() {
        return transport;
    }

    @Override
    public String displayName() {
        return displayName;
    }

    @Override
    public String endpointDescription() {
        return "POST " + route;
    }

    @Override
    public void start() {
        markStarted();
    }

    @Override
    public void stop() {
        markStopped();
    }
}
