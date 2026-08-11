package com.aquagrid.platform.iot.receiver.application.validator;

import com.aquagrid.platform.common.error.ErrorCode;
import com.aquagrid.platform.iot.domain.model.Device;
import com.aquagrid.platform.iot.receiver.domain.model.ReceptionContext;
import com.aquagrid.platform.iot.receiver.domain.model.Rejection;
import com.aquagrid.platform.iot.receiver.spi.PayloadValidator;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

/**
 * Refuses telemetry from a device whose lifecycle state does not accept it.
 *
 * <p>Only {@code DECOMMISSIONED} is refused, and the narrowness is the point. A decommissioned
 * meter has been physically removed and its readings closed off for billing; accepting more would
 * reopen a settled account. Every other state still reports:
 *
 * <ul>
 *   <li>{@code PROVISIONED} — registered but not yet marked installed. Its first uplink is
 *       frequently how an installer confirms the fitting worked, and rejecting it would break
 *       commissioning for the sake of a status field nobody has got round to updating.</li>
 *   <li>{@code FAULTY} — a device flagged faulty is precisely the one whose telemetry is being
 *       watched to find out what is wrong with it.</li>
 *   <li>{@code INACTIVE} — administratively paused, not gone. Its readings still describe the
 *       network.</li>
 * </ul>
 *
 * <p>Pre-parse: there is no point decoding a payload the receiver has already decided not to keep.
 */
@Component
public class DeviceStateValidator implements PayloadValidator {

    public static final int ORDER = 30;

    private static final Set<String> REFUSED = Set.of("DECOMMISSIONED");

    @Override
    public String name() {
        return "DEVICE_STATE";
    }

    @Override
    public Optional<Rejection> validate(ReceptionContext context) {
        Device device = context.getDevice();
        if (device == null || device.getStatus() == null) {
            return Optional.empty();
        }
        if (REFUSED.contains(device.getStatus().toUpperCase())) {
            return Optional.of(Rejection.of(ErrorCode.RECEIVER_DEVICE_NOT_OPERATIONAL,
                    "Device " + device.getDeviceCode() + " is " + device.getStatus()));
        }
        return Optional.empty();
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
