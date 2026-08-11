package com.aquagrid.platform.iot.receiver.domain.model;

/**
 * The terminal state of one reception, as stored on the packet log.
 *
 * <p>Three values, not one per failure mode: <em>why</em> a packet failed is the error code, and
 * duplicating that vocabulary here would guarantee the two drift. This answers only the question an
 * operator asks first — did the reading land? — and it is what the receiver's success-rate metric
 * and the packet-search filter are computed from.
 */
public enum ReceptionStatus {

    /** Decoded, attributed to a device and persisted as telemetry. */
    ACCEPTED,

    /** Recognised as already ingested. Counted separately: a rising duplicate rate is a fault
     *  signal (a gateway retransmitting because our acknowledgements are not reaching it), but it
     *  is not data loss and must not depress the success rate. */
    DUPLICATE,

    /** Refused. The accompanying error code says why, and the raw payload is kept for forensics. */
    REJECTED
}
