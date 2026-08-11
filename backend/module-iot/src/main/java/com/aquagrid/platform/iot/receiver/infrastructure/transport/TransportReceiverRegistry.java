package com.aquagrid.platform.iot.receiver.infrastructure.transport;

import com.aquagrid.platform.iot.receiver.application.service.ReceiverLogService;
import com.aquagrid.platform.iot.receiver.domain.model.ReceiverLog;
import com.aquagrid.platform.iot.receiver.spi.TransportReceiver;
import com.aquagrid.platform.iot.receiver.spi.TransportStatus;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Owns the lifecycle of every transport receiver in the deployment.
 *
 * <p>Transports are discovered as beans, keyed by the code they declare. Nothing enumerates them:
 * a receiver that is not enabled contributes no bean, so it has no route, no socket and no entry
 * here — the deployment's active transport list is a fact about the context rather than a list
 * someone has to keep in step with one.
 *
 * <p>Startup is fault-isolated on purpose. A broker that is unreachable, or a port already in use,
 * must not stop the application: the other transports are still able to take telemetry, and a
 * receiver that refuses to boot because one of eight listeners failed converts a partial outage
 * into a total one. The failure is logged, recorded to {@link ReceiverLog} so it survives log
 * rotation, and surfaced by the health indicator.
 */
@Slf4j
@Service
public class TransportReceiverRegistry {

    private final Map<String, TransportReceiver> receivers = new LinkedHashMap<>();
    private final ReceiverLogService receiverLog;

    public TransportReceiverRegistry(List<TransportReceiver> discovered,
                                     ReceiverLogService receiverLog) {
        this.receiverLog = receiverLog;
        for (TransportReceiver receiver : discovered) {
            TransportReceiver existing = receivers.put(receiver.transport().toUpperCase(), receiver);
            if (existing != null) {
                // Two receivers claiming one transport is a wiring mistake whose symptom would
                // otherwise be that one of them silently never runs.
                throw new IllegalStateException("Two receivers declare transport "
                        + receiver.transport() + ": " + existing.getClass().getName()
                        + " and " + receiver.getClass().getName());
            }
        }
    }

    @PostConstruct
    public void startAll() {
        if (receivers.isEmpty()) {
            log.warn("No transport receivers are enabled — the platform will accept no telemetry");
            return;
        }
        receivers.forEach((transport, receiver) -> {
            try {
                receiver.start();
                receiverLog.info(ReceiverLog.Events.TRANSPORT_STARTED, transport,
                        receiver.displayName() + " listening on " + receiver.endpointDescription(),
                        Map.of("endpoint", receiver.endpointDescription()));
            } catch (RuntimeException e) {
                log.error("Transport {} failed to start — other transports are unaffected",
                        transport, e);
                receiverLog.error(ReceiverLog.Events.TRANSPORT_FAILED, transport,
                        "Failed to start: " + e.getMessage(),
                        Map.of("exception", e.getClass().getSimpleName()));
            }
        });
        log.info("Receiver active on {} transport(s): {}", receivers.size(), receivers.keySet());
    }

    @PreDestroy
    public void stopAll() {
        receivers.forEach((transport, receiver) -> {
            try {
                receiver.stop();
                receiverLog.info(ReceiverLog.Events.TRANSPORT_STOPPED, transport,
                        receiver.displayName() + " stopped", Map.of());
            } catch (RuntimeException e) {
                // Shutdown continues regardless: one listener refusing to close must not leave the
                // remaining sockets open.
                log.warn("Transport {} failed to stop cleanly", transport, e);
            }
        });
    }

    public Optional<TransportReceiver> find(String transport) {
        return transport == null
                ? Optional.empty()
                : Optional.ofNullable(receivers.get(transport.toUpperCase()));
    }

    public List<TransportStatus> statuses() {
        return receivers.values().stream().map(TransportReceiver::status).toList();
    }

    public List<String> activeTransports() {
        return List.copyOf(receivers.keySet());
    }

    /** True when every enabled transport reports itself running — what the health check asks. */
    public boolean allRunning() {
        return receivers.values().stream().allMatch(receiver -> receiver.status().running());
    }
}
