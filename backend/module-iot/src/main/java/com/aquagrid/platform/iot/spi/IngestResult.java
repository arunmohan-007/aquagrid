package com.aquagrid.platform.iot.spi;

/**
 * The outcome of ingesting one {@link com.aquagrid.platform.iot.api.DeviceMessage}.
 *
 * <p>Sealed so the compiler forces every adapter to handle each case: a QoS-1 NB-IoT message must
 * be acknowledged only on {@link #ACCEPTED} or {@link #DUPLICATE}, and re-queued on
 * {@link #REJECTED}. Getting this switch incomplete is how messages get silently dropped.
 */
public sealed interface IngestResult permits IngestResult.Accepted, IngestResult.Rejected, IngestResult.Duplicate {

    /** Persisted and acknowledged. The message is durably ingested. */
    record Accepted(String deviceId) implements IngestResult {
    }

    /**
     * Refused. The device is unknown, the tenant is inactive, or the message is malformed.
     * The adapter should NOT ack a QoS message in this state if retrying could succeed.
     */
    record Rejected(String reason) implements IngestResult {
    }

    /**
     * Already ingested (frame counter replay / dedup hit). Safe to acknowledge — the platform has
     * already accounted for this message, so re-acking is harmless and dropping the duplicate is
     * correct.
     */
    record Duplicate(String deviceId) implements IngestResult {
    }
}
