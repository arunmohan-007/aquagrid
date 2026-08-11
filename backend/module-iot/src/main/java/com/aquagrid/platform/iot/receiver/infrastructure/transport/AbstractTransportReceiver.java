package com.aquagrid.platform.iot.receiver.infrastructure.transport;

import com.aquagrid.platform.iot.receiver.api.ReceiverGateway;
import com.aquagrid.platform.iot.receiver.api.ReceptionOutcome;
import com.aquagrid.platform.iot.receiver.domain.model.InboundPacket;
import com.aquagrid.platform.iot.receiver.spi.TransportReceiver;
import com.aquagrid.platform.iot.receiver.spi.TransportStatus;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Everything a transport receiver does that is not specific to its transport.
 *
 * <p>Template Method, and it earns its place by removing the three things every transport would
 * otherwise have to remember: hand the packet to the gateway and nothing else, never let an
 * exception escape into the wire's own error handling, and keep the counters the status endpoint
 * reads. Getting any of those wrong in one transport produces a receiver that is correct for seven
 * technologies and quietly broken for the eighth.
 *
 * <p>The important constraint it enforces is architectural: {@link #accept} is the <em>only</em>
 * route from a transport into the platform, and it goes through {@link ReceiverGateway}. A
 * transport cannot reach {@code TelemetryIngestPort}, cannot write a packet log, and cannot skip
 * authentication, because it has a reference to none of those things. The single-entry-point
 * guarantee is enforced by what subclasses are given rather than by a rule they are asked to follow.
 */
@Slf4j
public abstract class AbstractTransportReceiver implements TransportReceiver {

    private final ReceiverGateway gateway;

    private final AtomicLong packetsReceived = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private final AtomicReference<Instant> lastPacketAt = new AtomicReference<>();
    private volatile Instant startedAt;
    private volatile boolean running;

    protected AbstractTransportReceiver(ReceiverGateway gateway) {
        this.gateway = gateway;
    }

    /**
     * Delivers one packet to the receiver.
     *
     * <p>Never throws. A transport calls this from a socket read loop, a broker callback or an MVC
     * handler, and an exception escaping into any of those means something different and unhelpful
     * — a dropped connection, an unacknowledged message, a 500 with a stack trace. The outcome is
     * always a value the caller can act on.
     */
    protected final ReceptionOutcome accept(InboundPacket packet) {
        packetsReceived.incrementAndGet();
        lastPacketAt.set(packet.receivedAt());
        try {
            return gateway.receive(packet);
        } catch (RuntimeException e) {
            // ReceiverService already contains its own failures, so this is a last-resort guard
            // against a defect in the gateway itself taking a listener down with it.
            errors.incrementAndGet();
            log.error("Receiver gateway failed for {} packet {}", transport(), packet.packetId(), e);
            return ReceptionOutcome.reject(packet.packetId(),
                    com.aquagrid.platform.common.error.ErrorCode.INTERNAL_ERROR,
                    "Receiver failure");
        }
    }

    /** Counts a packet the transport could not even turn into an {@link InboundPacket}. */
    protected final void recordTransportError() {
        errors.incrementAndGet();
    }

    protected final void markStarted() {
        this.startedAt = Instant.now();
        this.running = true;
        log.info("{} receiver started on {}", displayName(), endpointDescription());
    }

    protected final void markStopped() {
        this.running = false;
        log.info("{} receiver stopped", displayName());
    }

    /** Open connections. Zero for stateless transports; overridden by the ones that hold sockets. */
    protected int activeConnections() {
        return 0;
    }

    @Override
    public TransportStatus status() {
        return TransportStatus.builder()
                .transport(transport())
                .displayName(displayName())
                .running(running)
                .endpoint(endpointDescription())
                .stateful(stateful())
                .activeConnections(activeConnections())
                .packetsReceived(packetsReceived.get())
                .errors(errors.get())
                .lastPacketAt(lastPacketAt.get())
                .startedAt(startedAt)
                .build();
    }

    protected final boolean isRunning() {
        return running;
    }
}
