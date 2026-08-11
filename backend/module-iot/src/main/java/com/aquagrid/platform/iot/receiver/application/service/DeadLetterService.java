package com.aquagrid.platform.iot.receiver.application.service;

import com.aquagrid.platform.common.error.ErrorCode;
import com.aquagrid.platform.iot.receiver.application.metrics.ReceiverMetrics;
import com.aquagrid.platform.iot.receiver.domain.model.DeadLetterPacket;
import com.aquagrid.platform.iot.receiver.domain.model.InboundPacket;
import com.aquagrid.platform.iot.receiver.domain.model.ReceptionContext;
import com.aquagrid.platform.iot.receiver.infrastructure.persistence.DeadLetterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Stores packets that failed for a reason that could stop being true.
 *
 * <p>The membership rule is the whole design, and it is narrow on purpose. A dead-letter queue that
 * catches every rejection is a bin: it fills with uplinks from a neighbouring utility's meters,
 * nobody can find the recoverable packets among them, and the queue stops being looked at — which
 * is the same as not having one.
 *
 * <p>So only failures whose cause is on <em>this</em> side are queued: an internal error, a stage
 * that threw, a timeout, a lost connection to the database. Replaying those after the fault is
 * fixed recovers the reading. Replaying an unknown-device rejection finds the same unknown device.
 *
 * <p>Why it matters more than it sounds: a gateway that receives no acknowledgement retries for a
 * while and then gives up, and the readings it was holding are gone for good. Storing the packet
 * means a replay after an outage reconstructs the interval, instead of leaving a hole in a
 * consumption series that will later be billed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeadLetterService {

    /**
     * The rejections worth keeping. Everything absent from this set is a statement about the packet
     * or the fleet, and would fail identically on replay.
     */
    private static final Set<ErrorCode> RECOVERABLE = Set.of(
            ErrorCode.INTERNAL_ERROR,
            ErrorCode.RECEIVER_TIMEOUT,
            ErrorCode.RECEIVER_CONNECTION_FAILED);

    private final DeadLetterRepository repository;
    private final ReceiverMetrics metrics;

    public static boolean isRecoverable(ErrorCode code) {
        return code != null && RECOVERABLE.contains(code);
    }

    /**
     * Queues a packet, in its own transaction.
     *
     * <p>{@code REQUIRES_NEW} for the same reason the packet log uses it: this row exists to
     * describe a failure, and enrolling it in the transaction that is failing would roll it back
     * along with everything else — leaving no record of the packet whose recovery is the point.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void store(ReceptionContext context) {
        ErrorCode code = context.getRejection().code();
        if (!isRecoverable(code)) {
            return;
        }
        try {
            InboundPacket packet = context.getPacket();
            DeadLetterPacket letter = new DeadLetterPacket();
            letter.setPacketId(packet.packetId());
            letter.setTransport(packet.transport());
            letter.setErrorCode(code.name());
            letter.setErrorDetail(context.getRejection().detail());
            letter.setPayload(packet.payload());
            letter.setContentType(packet.contentType());
            letter.setSourceIp(packet.sourceIp());
            letter.setReceivedAt(packet.receivedAt());
            letter.setEnvelope(envelope(packet));
            context.deviceIfResolved().ifPresent(device -> {
                letter.setDeviceId(device.getId());
                letter.setOrganizationId(device.getOrganizationId());
            });

            repository.save(letter);
            metrics.recordDeadLetter(packet.transport(), code.name());
        } catch (RuntimeException e) {
            // The fault that caused the dead letter may be the same fault preventing it being
            // written — the database being unreachable produces both. Nothing further to do but say
            // so loudly; throwing would replace a lost reading with a lost reading and a 500.
            log.error("Failed to dead-letter packet {}", context.packetId(), e);
        }
    }

    /**
     * Everything needed to reconstruct the packet.
     *
     * <p>The identifier claims and transport attributes, not just the bytes. A replay without them
     * is not the same packet: an MQTT publish stripped of its topic cannot be resolved, and would
     * dead-letter again on replay for a reason that has nothing to do with the original fault.
     */
    private static Map<String, Object> envelope(InboundPacket packet) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        Map<String, String> identifiers = new LinkedHashMap<>();
        packet.identifiers().forEach((type, value) -> identifiers.put(type.name(), value));
        envelope.put("identifiers", identifiers);
        envelope.put("attributes", packet.attributes());
        envelope.put("correlationId", packet.correlationId());
        // Credentials are deliberately absent. A replay is performed by an authenticated operator
        // under their own authority; storing device secrets here would put a durable copy of every
        // credential that ever failed into a table nobody thinks of as a secret store.
        return envelope;
    }
}
