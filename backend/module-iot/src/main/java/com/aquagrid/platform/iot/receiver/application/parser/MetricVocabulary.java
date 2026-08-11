package com.aquagrid.platform.iot.receiver.application.parser;

import com.aquagrid.platform.iot.api.DeviceMessage;
import com.aquagrid.platform.iot.receiver.api.TelemetryEvent;

import java.util.Map;

/**
 * Maps vendor field names onto the platform's canonical metric names.
 *
 * <p>One table, shared by every parser. Before the receiver, each adapter carried its own
 * normalisation — {@code MqttAdapter} had a switch, {@code NbiotAdapter} had a list of explicit
 * copies, {@code ChirpStackLorawanAdapter} had its own names — which meant a vendor spelling
 * supported over MQTT was silently unrecognised over NB-IoT, and adding one meant finding every
 * copy. Centralising it is the concrete form of the brief's no-duplicated-code requirement.
 *
 * <p>Unrecognised names pass through unchanged rather than being dropped. A metric the platform has
 * no canonical name for is still a number the device measured, and the readings table has no
 * problem storing it under the vendor's own key; discarding it would lose data to satisfy a
 * vocabulary.
 */
public final class MetricVocabulary {

    /**
     * Vendor spelling (lower-cased) → canonical name.
     *
     * <p>Compiled from the field names in use across the meter vendors already integrated. Keys are
     * matched after lower-casing and stripping separators, so {@code flowRate}, {@code flow_rate}
     * and {@code FLOW-RATE} all land on the same entry without three table rows.
     */
    private static final Map<String, String> CANONICAL = Map.ofEntries(
            Map.entry("flowrate", DeviceMessage.Metrics.FLOW_RATE),
            Map.entry("flow", DeviceMessage.Metrics.FLOW_RATE),
            Map.entry("instantaneousflow", DeviceMessage.Metrics.FLOW_RATE),
            Map.entry("q", DeviceMessage.Metrics.FLOW_RATE),

            Map.entry("volume", DeviceMessage.Metrics.VOLUME),
            Map.entry("totalvolume", DeviceMessage.Metrics.VOLUME),
            Map.entry("cumulativevolume", DeviceMessage.Metrics.VOLUME),
            Map.entry("totalizer", DeviceMessage.Metrics.VOLUME),
            Map.entry("reading", DeviceMessage.Metrics.VOLUME),

            Map.entry("pressure", DeviceMessage.Metrics.PRESSURE),
            Map.entry("p", DeviceMessage.Metrics.PRESSURE),
            Map.entry("linepressure", DeviceMessage.Metrics.PRESSURE),

            Map.entry("battery", DeviceMessage.Metrics.BATTERY_VOLTAGE),
            Map.entry("batteryvoltage", DeviceMessage.Metrics.BATTERY_VOLTAGE),
            Map.entry("vbat", DeviceMessage.Metrics.BATTERY_VOLTAGE),
            Map.entry("batt", DeviceMessage.Metrics.BATTERY_VOLTAGE),

            Map.entry("temperature", DeviceMessage.Metrics.TEMPERATURE),
            Map.entry("temp", DeviceMessage.Metrics.TEMPERATURE),

            Map.entry("tamper", DeviceMessage.Metrics.TAMPER),
            Map.entry("tamperalarm", DeviceMessage.Metrics.TAMPER),
            Map.entry("reverseflow", DeviceMessage.Metrics.REVERSE_FLOW),
            Map.entry("backflow", DeviceMessage.Metrics.REVERSE_FLOW),
            Map.entry("leak", DeviceMessage.Metrics.LEAK),
            Map.entry("leakalarm", DeviceMessage.Metrics.LEAK),
            Map.entry("leakage", DeviceMessage.Metrics.LEAK));

    /** Canonical metric → the alarm flag it raises when non-zero. */
    private static final Map<String, String> FLAG_FOR_METRIC = Map.of(
            DeviceMessage.Metrics.TAMPER, TelemetryEvent.Flags.TAMPER,
            DeviceMessage.Metrics.REVERSE_FLOW, TelemetryEvent.Flags.REVERSE_FLOW,
            DeviceMessage.Metrics.LEAK, TelemetryEvent.Flags.LEAK);

    private MetricVocabulary() {
    }

    /** Canonical name for a vendor field, or the field itself when there is no mapping. */
    public static String canonicalise(String vendorField) {
        if (vendorField == null || vendorField.isBlank()) {
            return null;
        }
        String normalised = vendorField.toLowerCase()
                .replace("_", "").replace("-", "").replace(" ", "");
        return CANONICAL.getOrDefault(normalised, vendorField);
    }

    /**
     * The alarm flag a metric raises, or null if it raises none.
     *
     * <p>Lets the event builder derive flags from readings uniformly, so a parser that decoded a
     * status byte and one that read a JSON field produce the same flag set without either knowing
     * about the other.
     */
    public static String flagFor(String canonicalMetric, Double value) {
        if (value == null || value == 0.0) {
            return null;
        }
        return FLAG_FOR_METRIC.get(canonicalMetric);
    }

    /** True when the metric is a 0/1 condition rather than a measurement. */
    public static boolean isFlagMetric(String canonicalMetric) {
        return FLAG_FOR_METRIC.containsKey(canonicalMetric);
    }
}
