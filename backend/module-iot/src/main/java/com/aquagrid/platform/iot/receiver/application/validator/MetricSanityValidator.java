package com.aquagrid.platform.iot.receiver.application.validator;

import com.aquagrid.platform.common.error.ErrorCode;
import com.aquagrid.platform.iot.api.DeviceMessage;
import com.aquagrid.platform.iot.receiver.domain.model.ParsedTelemetry;
import com.aquagrid.platform.iot.receiver.domain.model.ReceptionContext;
import com.aquagrid.platform.iot.receiver.domain.model.Rejection;
import com.aquagrid.platform.iot.receiver.infrastructure.config.ReceiverProperties;
import com.aquagrid.platform.iot.receiver.spi.PayloadValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * Rejects decoded readings that cannot be physically true.
 *
 * <p>This catches decode faults, not faulty meters, and the distinction sets the bounds. A meter
 * reading 40 bar is reporting a real over-pressure and must be ingested so an alarm fires; a meter
 * reading 4×10^7 bar has been misdecoded — wrong endianness, wrong scaling factor, a frame read at
 * the wrong offset. The ranges below are therefore set at the edge of physical possibility rather
 * than at the edge of normal operation. Anything narrower would silently discard the readings the
 * platform exists to raise alarms about.
 *
 * <p>The metric count is bounded for a different reason: each reading becomes a row, so one packet
 * declaring ten thousand metrics is a write amplification primitive.
 */
@Component
@RequiredArgsConstructor
public class MetricSanityValidator implements PayloadValidator {

    public static final int ORDER = 40;

    /** Canonical metric → the widest physically plausible range. */
    private static final Map<String, double[]> PLAUSIBLE = Map.of(
            // A 600mm trunk main at 5 m/s is ~85,000 L/min. Nothing on a distribution network
            // exceeds this; a value beyond it is a scaling error.
            DeviceMessage.Metrics.FLOW_RATE, new double[]{-100_000, 100_000},
            // Cumulative and monotonic in practice, but a meter rollover is legitimate, so only the
            // absurd is refused. 10^10 litres is 10 million m³ — decades of a large industrial supply.
            DeviceMessage.Metrics.VOLUME, new double[]{-1e10, 1e10},
            // Distribution runs 0-16 bar; pumps and transients reach 40. Negative is real (vacuum
            // on a draining main) but bounded.
            DeviceMessage.Metrics.PRESSURE, new double[]{-5, 100},
            // A 3.6V lithium cell, with headroom for a mains-backed unit.
            DeviceMessage.Metrics.BATTERY_VOLTAGE, new double[]{0, 30},
            // Buried and above-ground pipework across any climate this ships to.
            DeviceMessage.Metrics.TEMPERATURE, new double[]{-50, 150});

    private final ReceiverProperties properties;

    @Override
    public String name() {
        return "METRIC_SANITY";
    }

    @Override
    public Phase phase() {
        return Phase.POST_PARSE;
    }

    @Override
    public Optional<Rejection> validate(ReceptionContext context) {
        ParsedTelemetry telemetry = context.getTelemetry();
        if (telemetry == null || telemetry.metrics().isEmpty()) {
            return Optional.empty();
        }

        int limit = properties.limits().maxMetricsPerPacket();
        if (telemetry.metrics().size() > limit) {
            return Optional.of(Rejection.of(ErrorCode.RECEIVER_VALIDATION_FAILED,
                    "Packet declares " + telemetry.metrics().size()
                            + " metrics, above the limit of " + limit));
        }

        for (Map.Entry<String, Double> metric : telemetry.metrics().entrySet()) {
            double[] range = PLAUSIBLE.get(metric.getKey());
            if (range == null) {
                // An unrecognised metric has no range to check against. Passed through rather than
                // refused: the platform stores vendor-specific readings it has no opinion about,
                // and inventing a bound for one would be a guess enforced as a rule.
                continue;
            }
            Double value = metric.getValue();
            if (value == null || value.isNaN() || value.isInfinite()
                    || value < range[0] || value > range[1]) {
                return Optional.of(Rejection.of(ErrorCode.RECEIVER_VALIDATION_FAILED,
                        "Metric " + metric.getKey() + " value " + value
                                + " is outside the plausible range ["
                                + range[0] + ", " + range[1] + "] — likely a decode fault"));
            }
        }
        return Optional.empty();
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
