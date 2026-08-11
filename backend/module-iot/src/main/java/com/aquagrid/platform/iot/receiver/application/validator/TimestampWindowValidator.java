package com.aquagrid.platform.iot.receiver.application.validator;

import com.aquagrid.platform.common.error.ErrorCode;
import com.aquagrid.platform.iot.receiver.domain.model.ParsedTelemetry;
import com.aquagrid.platform.iot.receiver.domain.model.ReceptionContext;
import com.aquagrid.platform.iot.receiver.domain.model.Rejection;
import com.aquagrid.platform.iot.receiver.infrastructure.config.ReceiverProperties;
import com.aquagrid.platform.iot.receiver.spi.PayloadValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Bounds how far a device's own clock may be from the receiver's.
 *
 * <p>The two bounds are deliberately asymmetric, because the two directions mean different things.
 *
 * <p><b>Backwards is normal.</b> An NB-IoT device waking from power-saving mode, or a LoRaWAN meter
 * in a basement that finally got a gateway, legitimately delivers hours or days of buffered
 * readings. The past window has to accommodate that or a store-and-forward fleet loses exactly the
 * data it was built to preserve — so it defaults to a generous seven days.
 *
 * <p><b>Forwards is not.</b> A device cannot legitimately know a time that has not happened. A
 * future timestamp is a dead battery that reset the RTC, a firmware bug, or someone trying to
 * pre-place a reading — and a reading dated forwards is worse than a lost one: it sits at the head
 * of the series, defeats the replay window it should have fallen inside, and corrupts every
 * consumption calculation that reads "latest". Hence minutes, not days.
 */
@Component
@RequiredArgsConstructor
public class TimestampWindowValidator implements PayloadValidator {

    public static final int ORDER = 20;

    private final ReceiverProperties properties;

    @Override
    public String name() {
        return "TIMESTAMP_WINDOW";
    }

    @Override
    public Phase phase() {
        return Phase.POST_PARSE;
    }

    @Override
    public Optional<Rejection> validate(ReceptionContext context) {
        ParsedTelemetry telemetry = context.getTelemetry();
        if (telemetry == null || telemetry.observedAt() == null) {
            // No device clock to check. The event builder substitutes reception time and records
            // that it did, which is honest; there is nothing here to reject.
            return Optional.empty();
        }

        Instant observedAt = telemetry.observedAt();
        Instant receivedAt = context.getPacket().receivedAt();

        Duration future = Duration.between(receivedAt, observedAt);
        if (future.compareTo(properties.security().maxClockSkewFuture()) > 0) {
            return Optional.of(Rejection.of(ErrorCode.RECEIVER_VALIDATION_FAILED,
                    "Device timestamp is " + future.toSeconds() + "s in the future"));
        }

        Duration past = Duration.between(observedAt, receivedAt);
        if (past.compareTo(properties.security().maxClockSkewPast()) > 0) {
            return Optional.of(Rejection.of(ErrorCode.RECEIVER_VALIDATION_FAILED,
                    "Device timestamp is " + past.toDays() + "d old, beyond the accepted window"));
        }
        return Optional.empty();
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
