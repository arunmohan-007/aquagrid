package com.aquagrid.platform.iot.receiver.api;

import com.aquagrid.platform.common.error.ErrorCode;

import java.util.UUID;

/**
 * What the receiver did with one inbound packet.
 *
 * <p>Sealed, for the same reason {@link com.aquagrid.platform.iot.spi.IngestResult} is: every
 * transport must decide how to acknowledge its wire, and the compiler is what stops a new transport
 * from silently dropping a case. The rules are not the same for each:
 *
 * <ul>
 *   <li>{@link Accepted} and {@link Duplicate} — acknowledge. A duplicate is already accounted for,
 *       and refusing to ack it makes the network retransmit forever.</li>
 *   <li>{@link Rejected} — do <b>not</b> acknowledge when a retry could succeed, which is exactly
 *       what {@link #retryable()} answers. An unknown device will still be unknown on the next
 *       attempt; a database timeout will not.</li>
 * </ul>
 *
 * <p>Every outcome carries the packet id so the caller can quote it to an operator, who can then
 * find the packet log row without knowing anything else about the request.
 */
public sealed interface ReceptionOutcome
        permits ReceptionOutcome.Accepted, ReceptionOutcome.Duplicate, ReceptionOutcome.Rejected {

    UUID packetId();

    /** Ingested, or already ingested. Either way the wire may be acknowledged. */
    default boolean acknowledgeable() {
        return !(this instanceof Rejected);
    }

    /** Persisted as telemetry. */
    record Accepted(UUID packetId, UUID deviceId, UUID eventId) implements ReceptionOutcome {
    }

    /** Recognised as an already-ingested packet: replayed frame counter, nonce or content hash. */
    record Duplicate(UUID packetId, UUID deviceId) implements ReceptionOutcome {
    }

    /**
     * Refused, with the reason named in the platform's error vocabulary so that the packet log,
     * the metrics tags and the RFC 7807 response all classify the failure identically.
     */
    record Rejected(UUID packetId, ErrorCode code, String detail) implements ReceptionOutcome {

        /**
         * Whether the same packet could succeed if delivered again.
         *
         * <p>Only infrastructure failures are retryable. Everything else is a statement about the
         * packet or the fleet — redelivering an unsigned packet or one from an unregistered device
         * produces the identical rejection and costs the gateway its retry budget.
         */
        public boolean retryable() {
            return code == ErrorCode.RECEIVER_TIMEOUT
                    || code == ErrorCode.RECEIVER_CONNECTION_FAILED
                    || code == ErrorCode.INTERNAL_ERROR;
        }
    }

    /** Convenience for the common rejection shape. */
    static Rejected reject(UUID packetId, ErrorCode code, String detail) {
        return new Rejected(packetId, code, detail);
    }
}
