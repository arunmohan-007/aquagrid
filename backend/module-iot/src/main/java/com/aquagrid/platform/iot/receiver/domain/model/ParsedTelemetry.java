package com.aquagrid.platform.iot.receiver.domain.model;

import lombok.Builder;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * What a parser got out of a payload, before the device is applied to it.
 *
 * <p>The intermediate between bytes and a {@link com.aquagrid.platform.iot.receiver.api.TelemetryEvent}.
 * It holds everything the payload itself can say — readings, flags, radio quality, the device's own
 * clock — and nothing that requires knowing which device sent it. That split is what keeps parsers
 * pure functions of their input: a parser needs no repository, no tenant and no clock beyond the
 * packet's, so it is trivially unit-testable and trivially safe to run concurrently.
 *
 * <p>{@code observedAt} may be null, and that is not an error. Plenty of payloads carry no
 * timestamp; the event builder then falls back to the reception time and records that it did, so a
 * downstream consumer can tell a device clock from a server clock.
 */
@Builder(toBuilder = true)
public record ParsedTelemetry(
        Instant observedAt,
        Map<String, Double> metrics,
        Set<String> alarmFlags,
        Double battery,
        Double signalStrength,
        Double signalToNoise,
        Integer frameCounter,
        Double latitude,
        Double longitude,
        Map<String, Object> raw,
        /** Which parser produced this, recorded on the packet log so a decode fault is traceable. */
        String parser
) {

    public ParsedTelemetry {
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
        alarmFlags = alarmFlags == null ? Set.of() : Set.copyOf(alarmFlags);
        raw = raw == null ? Map.of() : Map.copyOf(raw);
    }

    /** A keep-alive: the device reported in, with nothing to say. Still proof it is alive. */
    public static ParsedTelemetry keepAlive(String parser) {
        return ParsedTelemetry.builder().parser(parser).build();
    }

    public boolean isEmpty() {
        return metrics.isEmpty() && alarmFlags.isEmpty();
    }

    /** Accumulates readings as a parser walks a payload, skipping nulls so callers need no guards. */
    public static final class Accumulator {
        private final Map<String, Double> metrics = new LinkedHashMap<>();
        private final Set<String> flags = new LinkedHashSet<>();
        private final Map<String, Object> raw = new LinkedHashMap<>();

        public Accumulator metric(String name, Double value) {
            if (name != null && value != null && !value.isNaN() && !value.isInfinite()) {
                metrics.put(name, value);
            }
            return this;
        }

        /** Records a flag, and mirrors it as a 0/1 metric so it also lands in the readings table. */
        public Accumulator flag(String name, boolean asserted, String metricName) {
            if (metricName != null) {
                metrics.put(metricName, asserted ? 1.0 : 0.0);
            }
            if (asserted) {
                flags.add(name);
            }
            return this;
        }

        public Accumulator raw(String key, Object value) {
            if (value != null) {
                raw.put(key, value);
            }
            return this;
        }

        public Map<String, Double> metrics() {
            return metrics;
        }

        public Set<String> flags() {
            return flags;
        }

        public Map<String, Object> raw() {
            return raw;
        }
    }
}
