package com.aquagrid.platform.iot.simulator;

import com.aquagrid.platform.iot.dataconfig.api.DeviceParameterApi;
import com.aquagrid.platform.iot.dataconfig.api.ParameterDefinition;
import com.aquagrid.platform.iot.dataconfig.domain.model.ParameterDataType;
import com.aquagrid.platform.iot.receiver.application.parser.MetricVocabulary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Decides what a simulated device reports beyond the water-meter model, from its data configuration.
 *
 * <p>{@link SimulatedMeter} models a water meter: a register that only climbs, a cell that drains, a
 * demand curve, and the faults the alarm and NRW modules are built to detect. That is the right model
 * for a meter and describes nothing else in the estate. A pump monitor reports voltage, current,
 * power, running hours and a fault word; a level sensor reports a level. Before this class, the only
 * way to simulate one was to write a second model in Java per device type — which is the shape the
 * data configuration module exists to replace.
 *
 * <p>So the configuration <em>is</em> the model. A device's configured parameters say what it is
 * expected to send, and this generates a plausible value for each one the meter model does not
 * already produce, under the vendor spelling the parameter declares.
 *
 * <h2>What this deliberately is not</h2>
 *
 * <p>It is not a simulator-specific ingestion path, and it adds no marker to the wire. The generated
 * fields are ordinary payload keys, indistinguishable from the ones a physical unit would send, and
 * they travel through the same {@code ReceiverGateway}, the same parsers and the same validation. The
 * one record that a reading is synthetic remains {@code DeviceSource} on the device row — see
 * {@code DeviceSimulator}. A field on the payload saying "simulated" would be a second answer to that
 * question and the first thing to be forgotten by a query written six months from now.
 *
 * <h2>Extra test parameters</h2>
 *
 * <p>{@link #setExtraTestParameters} adds fields that are deliberately <b>not</b> configured, which
 * is the only way to exercise the module's central promise end to end: that an unknown parameter is
 * accepted, stored whole and offered for configuration rather than rejected. Without it, testing that
 * path means hand-crafting a payload with curl and hoping it resembles what a device sends. Off by
 * default, because a fleet that permanently emits undescribed fields would keep the discovery queue
 * permanently non-empty and train operators to ignore it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "aquagrid.iot.transports", name = "simulator", havingValue = "true")
public class ConfiguredParameterComposer {

    /**
     * Parameters the meter model already emits, under their canonical names.
     *
     * <p>Skipped so configuration cannot fight the physics. A cumulative register that only climbs is
     * the one number a consumption simulator must not get wrong, and generating a second value for
     * {@code volume} from a min/max range would overwrite it with noise.
     *
     * <p>Compared against the <em>canonicalised</em> parameter name, not the name as configured. A
     * tenant that catalogues its battery as {@code battery} rather than {@code battery_voltage} has
     * named the same physical quantity, and matching only on the canonical spelling would let the
     * composer overwrite the model's 3.6 V cell with a number drawn from a percentage range — which
     * {@code MetricSanityValidator} then rightly refuses as a decode fault, silencing the whole
     * fleet. That is the failure this set exists to prevent, and it is invisible until somebody
     * configures a device type the simulator happens to use.
     */
    private static final Set<String> MODELLED = Set.of(
            "flow_rate", "volume", "battery_voltage", "tamper", "reverse_flow", "leak", "rssi", "snr");

    /**
     * Undescribed fields, for exercising the discovery path.
     *
     * <p>Named after things a real device plausibly reports and the platform has no opinion about,
     * so a developer reading the discovery screen sees what a genuine firmware update would look
     * like rather than {@code test1}, {@code test2}.
     */
    private static final String[] PROBE_KEYS = {"motor_temperature", "powerFactor", "frequency"};

    private final DeviceParameterApi parameterApi;

    /**
     * Whether to emit deliberately unconfigured fields. Runtime, not configuration: it is switched on
     * for a validation exercise and off again, and a restart to do either would make it unused.
     */
    private volatile boolean extraTestParameters;

    /**
     * The extra fields this meter should report on this uplink, by payload key.
     *
     * <p>Values are seeded from the device id and the parameter name, so a given meter reports the
     * same characteristic value for the same parameter on every run — the discipline the rest of the
     * simulator follows, and what makes a simulated reproduction of a reported bug actually
     * reproduce it.
     *
     * @param alreadyPresent keys the meter model has already written into this payload. The model
     *                       always wins: it is the one thing here that is physics rather than
     *                       configuration, and a composed value landing on top of a cumulative
     *                       register would post negative consumption for the interval
     */
    public Map<String, Object> compose(SimulatedMeter meter, long tick, Set<String> alreadyPresent) {
        Map<String, Object> fields = new LinkedHashMap<>();
        try {
            Map<String, ParameterDefinition> configured = parameterApi.effectiveForDevice(
                    meter.organizationId(), meter.deviceId(), meter.deviceType());
            for (Map.Entry<String, ParameterDefinition> entry : configured.entrySet()) {
                ParameterDefinition definition = entry.getValue();
                if (alreadyPresent.contains(entry.getKey())
                        || MODELLED.contains(MetricVocabulary.canonicalise(definition.parameterName()))
                        || !definition.dataType().isReading()) {
                    continue;
                }
                Object value = valueFor(meter, definition, tick);
                if (value != null) {
                    fields.put(entry.getKey(), value);
                }
            }
        } catch (RuntimeException e) {
            // A simulator that stopped emitting because a parameter is misconfigured would be worse
            // than one that emits only its meter model — the fleet is usually running so that
            // somebody can find that very misconfiguration.
            log.warn("Could not compose configured parameters for {}: {}",
                    meter.deviceCode(), e.getMessage());
        }
        if (extraTestParameters) {
            probeFields(meter, tick).forEach((key, value) -> {
                if (!alreadyPresent.contains(key)) {
                    fields.put(key, value);
                }
            });
        }
        return fields;
    }

    /**
     * A plausible value for one configured parameter.
     *
     * <p>In order of how much the configuration actually said: the declared sample or default first,
     * since an administrator who typed one has told us what this parameter looks like; then the
     * midpoint of a declared range; then a characteristic placeholder. Whatever the source, the
     * value is jittered so a chart is not a flat line, and kept <em>inside</em> the configured range
     * — a simulator that spontaneously produced OUT_OF_RANGE readings would fire alarms nobody asked
     * for, and the way to exercise that path is to inject a fault on purpose.
     */
    private static Object valueFor(SimulatedMeter meter, ParameterDefinition definition, long tick) {
        Random rng = seeded(meter, definition.parameterName());
        Double sample = numeric(definition.sampleValue());
        if (sample == null) {
            sample = numeric(definition.defaultValue());
        }
        Double min = definition.minValue();
        Double max = definition.maxValue();

        if (definition.dataType() == ParameterDataType.BOOLEAN) {
            // Mostly clear, occasionally set. A flag that is always false never exercises a rule and
            // one that flickers every uplink is noise, so it turns over on a slow cycle per device.
            return ((tick + Math.abs(meter.deviceId().hashCode())) % 17) == 0;
        }

        double base;
        if (sample != null) {
            base = sample;
        } else if (min != null && max != null) {
            base = (min + max) / 2.0;
        } else if (min != null) {
            base = min + 1;
        } else if (max != null) {
            base = max - 1;
        } else {
            // No sample and no range: a small positive number, characteristic of this device so a
            // series is readable rather than identical across the fleet.
            base = 1 + Math.floorMod(rng.nextInt(), 100);
        }

        // ±3% drift, then clamped back inside whatever the configuration allows.
        double value = base * (0.97 + rng.nextDouble() * 0.06);
        if (min != null) value = Math.max(min, value);
        if (max != null) value = Math.min(max, value);
        value = definition.dataType().round(value, definition.decimalPrecision() == null
                ? 2 : definition.decimalPrecision());

        return definition.dataType().isNumeric() && !definition.dataType().usesPrecision()
                ? Math.round(value)
                : value;
    }

    /**
     * Fields no configuration describes.
     *
     * <p>Values that look like real measurements rather than obvious test data, because the whole
     * point is to see the discovery screen as it will look when a firmware update genuinely adds a
     * sensor — including whether the detected data type is a useful guess.
     */
    private static Map<String, Object> probeFields(SimulatedMeter meter, long tick) {
        Random rng = seeded(meter, "probe");
        Map<String, Object> probes = new LinkedHashMap<>();
        probes.put(PROBE_KEYS[0], Math.round((38 + rng.nextDouble() * 14) * 10) / 10.0);
        probes.put(PROBE_KEYS[1], Math.round((0.85 + rng.nextDouble() * 0.12) * 100) / 100.0);
        probes.put(PROBE_KEYS[2], 50 + (tick % 2 == 0 ? 0 : 1) * 0.1);
        return probes;
    }

    /** Deterministic per device and parameter — see the class comment on reproducibility. */
    private static Random seeded(SimulatedMeter meter, String parameterName) {
        return new Random(meter.deviceId().getMostSignificantBits() ^ parameterName.hashCode());
    }

    private static Double numeric(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Double.valueOf(raw.trim());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    // ---- Control -------------------------------------------------------------------------------

    public void setExtraTestParameters(boolean enabled) {
        this.extraTestParameters = enabled;
        log.info("Simulator extra (unconfigured) test parameters {}: {}",
                enabled ? "enabled" : "disabled",
                enabled ? String.join(", ", PROBE_KEYS) : "none");
    }

    public boolean isExtraTestParameters() {
        return extraTestParameters;
    }

    /** The keys emitted when extra test parameters are on, so the console can name them. */
    public static java.util.List<String> probeKeys() {
        return java.util.List.of(PROBE_KEYS);
    }
}
