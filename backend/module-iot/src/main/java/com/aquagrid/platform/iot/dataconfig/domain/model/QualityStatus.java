package com.aquagrid.platform.iot.dataconfig.domain.model;

/**
 * What validation made of one received value.
 *
 * <p>The existence of this enum is what lets validation be strict and lossless at the same time.
 * Without somewhere to record a verdict, a validator that finds a bad value has exactly one thing it
 * can do with it, which is throw it away — and a pressure of 47 bar on a 10 bar main is the most
 * important reading of the day. Every value is stored; this says what the platform thinks of it.
 *
 * <p>Written to {@code iot.device_readings.quality} and, in aggregate, to the dashboards and reports
 * that need to distinguish "we measured 0" from "we have no measurement".
 */
public enum QualityStatus {

    /** Configured, readable as its declared type, and inside every rule declared for it. */
    VALID,

    /**
     * Configured, and could not be read as its declared type — a decimal parameter that arrived as
     * {@code "N/A"}, a boolean that arrived as {@code 7}.
     *
     * <p>Usually a configuration fault rather than a device fault, which is exactly why the value is
     * kept: the fix is to correct the parameter, and the historical rows then become readable
     * retrospectively because the payload they came from was never discarded.
     */
    INVALID,

    /** Readable, and outside the configured minimum or maximum. Kept, and flagged. */
    OUT_OF_RANGE,

    /**
     * Configured, mandatory, and absent from the packet. The reading row carries a NULL value.
     *
     * <p>Recorded rather than merely not-recorded, because "the device stopped sending pressure" and
     * "nobody ever asked for pressure" are different facts and only one of them is a fault. A gap in
     * a series says neither.
     */
    MISSING,

    /**
     * Not configured. The default, and not a defect.
     *
     * <p>It is the honest answer before an administrator has said anything about the parameter: the
     * value arrived, it was stored, and the platform has no opinion to offer about it yet. The
     * discovery queue is how that becomes an opinion.
     */
    UNKNOWN;

    /** True when the reading warrants an operator's attention. Drives the grid's filter. */
    public boolean isSuspect() {
        return this == INVALID || this == OUT_OF_RANGE || this == MISSING;
    }
}
