package com.aquagrid.platform.iot.dataconfig.domain.model;

/**
 * Where a discovered parameter has got to.
 *
 * <p>All three states leave the data alone. The discovery row is a note about a payload key, not the
 * key itself: the payloads are in {@code iot.device_raw_telemetry} and nothing in this enum reaches
 * them.
 */
public enum DiscoveryStatus {

    /** Seen, undescribed, and waiting for someone to say what it is. The default. */
    PENDING,

    /** Turned into a definition. {@code parameterId} names the row it became. */
    CONFIGURED,

    /**
     * Dismissed from the queue.
     *
     * <p>Deletes nothing, and that is the whole meaning of the state. The raw payloads stay, the
     * occurrence counters keep climbing, and a parameter ignored last year can be configured this
     * year with its entire history intact. It exists so that a vendor's {@code fw_build} field,
     * which nobody will ever chart, stops competing for attention with the {@code motor_temperature}
     * that someone should look at — without pretending the field was never sent.
     */
    IGNORED;

    /** Resolves a name from the API or the database; null when it names no status. */
    public static DiscoveryStatus from(String name) {
        if (name == null) {
            return null;
        }
        for (DiscoveryStatus status : values()) {
            if (status.name().equalsIgnoreCase(name.trim())) {
                return status;
            }
        }
        return null;
    }
}
