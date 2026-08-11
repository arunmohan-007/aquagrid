package com.aquagrid.platform.iot.domain.model;

import com.aquagrid.platform.iot.api.DeviceMessage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What each metric <em>is</em>: what to call it, what unit it carries, how to read it, and which
 * group it belongs to.
 *
 * <p>One table, served to the client rather than duplicated in it. The platform already learned this
 * lesson twice — {@code MetricVocabulary} exists because every transport adapter carried its own
 * copy of vendor-name normalisation, and the registration form reads its fields from
 * {@link CommunicationProfile} for the same reason. A metric's unit lived in a {@code switch} inside
 * the ingest service and its label in a TypeScript map in the browser, which meant adding a metric
 * required edits in two languages that nothing checked for agreement.
 *
 * <p>The grouping is the part an operator actually reads. A device reports a dozen numbers and they
 * are not of equal kind: consumption is what the bill is built from, pressure is what the network is
 * judged by, battery and radio quality say whether the device will still be reporting next month,
 * and the flags are conditions rather than measurements. Presenting them as one flat list of
 * name-value pairs makes the reader do that sorting every time.
 */
public final class MetricCatalog {

    private MetricCatalog() {
    }

    /**
     * How a value should be read.
     *
     * <p>Not cosmetic: it decides whether a chart makes sense. Plotting a cumulative register as a
     * line shows a ramp that says nothing — the interesting quantity is its difference between two
     * points — while plotting a flag as a line draws a meaningless staircase between 0 and 1.
     */
    public enum Kind {
        /** An instantaneous measurement. Meaningful to plot and to average. */
        MEASUREMENT,
        /**
         * A cumulative register that only climbs. Its <em>difference</em> is the quantity of
         * interest; the absolute value is only a meter reading.
         */
        COUNTER,
        /** A 0/1 condition. Read as set or clear, never averaged. */
        FLAG
    }

    /** The groups an operator reads a device's telemetry in. */
    public enum Category {
        /** What the bill is built from: the register and the rate feeding it. */
        CONSUMPTION("Meter reading"),
        /** What the network is judged by. */
        PRESSURE("Pressure"),
        /** Whether the device will still be reporting next month. */
        DEVICE_HEALTH("Device health"),
        /** Conditions the alarm engine acts on, rather than quantities. */
        CONDITION("Conditions"),
        ENVIRONMENT("Environment"),
        /**
         * Anything the platform has no opinion about. Vendor-specific readings are stored under
         * their own key rather than discarded, so they must have somewhere to be displayed —
         * dropping them from the view would hide data the device actually sent.
         */
        OTHER("Other readings");

        private final String label;

        Category(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * One metric's definition.
     *
     * @param metric   canonical name, as stored in {@code iot.device_readings.metric}
     * @param label    what an operator reads
     * @param unit     canonical unit, so two devices' readings are comparable. Null for flags
     * @param kind     how to read the value — see {@link Kind}
     * @param category which group it belongs to
     */
    public record Definition(String metric, String label, String unit, Kind kind, Category category) {
    }

    private static final Map<String, Definition> BY_METRIC = new LinkedHashMap<>();

    private static void define(String metric, String label, String unit, Kind kind, Category category) {
        BY_METRIC.put(metric, new Definition(metric, label, unit, kind, category));
    }

    static {
        define(DeviceMessage.Metrics.VOLUME, "Meter reading", "L", Kind.COUNTER, Category.CONSUMPTION);
        define(DeviceMessage.Metrics.FLOW_RATE, "Flow rate", "L/min", Kind.MEASUREMENT, Category.CONSUMPTION);

        define(DeviceMessage.Metrics.PRESSURE, "Pressure", "bar", Kind.MEASUREMENT, Category.PRESSURE);

        define(DeviceMessage.Metrics.BATTERY_VOLTAGE, "Battery", "V", Kind.MEASUREMENT, Category.DEVICE_HEALTH);
        // Radio quality arrives as a metric as well as a column: parsers collect every numeric field
        // a payload carries, and carrier webhooks put rssi and snr in the body beside the readings.
        // Catalogued rather than left to fall through to "Other" — the platform understands these,
        // they are the first thing consulted when a device starts missing uplinks, and they belong
        // beside the battery rather than in the bucket for things nothing has an opinion about.
        define("rssi", "Signal strength", "dBm", Kind.MEASUREMENT, Category.DEVICE_HEALTH);
        define("snr", "Signal-to-noise", "dB", Kind.MEASUREMENT, Category.DEVICE_HEALTH);

        define(DeviceMessage.Metrics.TEMPERATURE, "Temperature", "°C", Kind.MEASUREMENT, Category.ENVIRONMENT);

        // Unit "flag", not null, because that is what is already written on every existing row:
        // this catalogue took the unit mapping over from TelemetryIngestService, and changing the
        // value here would silently split the column into rows written before and after the change.
        // Nothing renders it — a FLAG is displayed as set or clear, and the client branches on
        // `kind` rather than on the unit string.
        define(DeviceMessage.Metrics.TAMPER, "Tamper", "flag", Kind.FLAG, Category.CONDITION);
        define(DeviceMessage.Metrics.REVERSE_FLOW, "Reverse flow", "flag", Kind.FLAG, Category.CONDITION);
        define(DeviceMessage.Metrics.LEAK, "Leak", "flag", Kind.FLAG, Category.CONDITION);
    }

    /** Every defined metric, in display order. */
    public static List<Definition> all() {
        return List.copyOf(BY_METRIC.values());
    }

    /**
     * The definition for a metric, inventing one where the platform has none.
     *
     * <p>Never null. A vendor-specific reading the platform has no opinion about is still a number
     * the device measured and the readings table stores it under the vendor's own key; returning
     * null here would make it disappear from the only screen that shows it.
     */
    public static Definition of(String metric) {
        Definition known = BY_METRIC.get(metric);
        if (known != null) {
            return known;
        }
        return new Definition(metric, metric, null, Kind.MEASUREMENT, Category.OTHER);
    }

    /**
     * Whether the platform ships an opinion about this metric.
     *
     * <p>Distinct from {@link #of}, which never returns null because a vendor-specific reading still
     * has to be displayable. This answers the narrower question the discovery queue asks: has anyone
     * already said what this is? A metric defined here arrives with a label, a unit, a kind and a
     * category, so listing it as undescribed would ask an administrator to configure something that
     * is already working — and a queue whose first rows are {@code flowRate} and {@code battery} is
     * a queue nobody scrolls past to reach the {@code motor_temperature} that actually needs a
     * decision.
     */
    public static boolean isCatalogued(String metric) {
        return metric != null && BY_METRIC.containsKey(metric);
    }

    /**
     * Canonical unit for a metric, or null where it has none.
     *
     * <p>Called by {@code TelemetryIngestService} as each reading is written, so the unit on the row
     * and the unit on the screen come from the same declaration.
     */
    public static String unitOf(String metric) {
        Definition definition = BY_METRIC.get(metric);
        return definition == null ? null : definition.unit();
    }
}
