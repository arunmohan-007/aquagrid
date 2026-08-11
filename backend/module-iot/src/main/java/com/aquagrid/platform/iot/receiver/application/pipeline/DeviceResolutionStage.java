package com.aquagrid.platform.iot.receiver.application.pipeline;

import com.aquagrid.platform.common.error.ErrorCode;
import com.aquagrid.platform.iot.domain.model.Device;
import com.aquagrid.platform.iot.receiver.application.resolver.DeviceResolver;
import com.aquagrid.platform.iot.receiver.application.security.ReceiverRateLimiter;
import com.aquagrid.platform.iot.receiver.domain.model.ReceptionContext;
import com.aquagrid.platform.iot.receiver.spi.ReceiverStage;
import com.aquagrid.platform.security.ratelimit.RateLimitDecision;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Binds the packet to a registered device — and, with it, to a tenant.
 *
 * <p><b>This is where multi-tenancy is established.</b> The tenant is taken from the resolved
 * device row and from nowhere else: not from a header, not from a payload field, not from the
 * credential that authenticated the packet. A gateway is a shared piece of infrastructure that may
 * legitimately serve several municipalities, so letting it name the tenant would let one
 * municipality's traffic be filed against another's meters — a data-integrity failure that would
 * surface as a billing dispute rather than as an error.
 *
 * <p>An unknown device is a rejection, never an exception. On any real network a gateway hears
 * meters that are not registered — a neighbouring utility's fleet, a device commissioned in the
 * field but never entered — and every one of those uplinks arrives here. They are logged and
 * counted, and they populate the unattributed-packet view, which in practice is the commissioning
 * queue.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceResolutionStage implements ReceiverStage {

    private final DeviceResolver deviceResolver;
    private final ReceiverRateLimiter rateLimiter;

    @Override
    public String name() {
        return "DEVICE_RESOLUTION";
    }

    @Override
    @Transactional(readOnly = true)
    public Decision execute(ReceptionContext context) {
        Optional<Device> resolved = deviceResolver.resolve(context);
        if (resolved.isEmpty()) {
            context.reject(ErrorCode.RECEIVER_UNKNOWN_DEVICE,
                    "No registered device matches the identifiers in this packet");
            return Decision.HALT;
        }

        Device device = resolved.get();
        context.resolvedDevice(device);

        // Per-device limiting happens here rather than at the gate, because until now there was no
        // device to limit. Keyed on the device, not the source: a meter that roams between two
        // gateways is one device, and limiting per source would silently grant it double.
        RateLimitDecision limit = rateLimiter.checkDevice(device.getId());
        if (!limit.allowed()) {
            context.reject(ErrorCode.RATE_LIMIT_EXCEEDED,
                    "Device " + device.getDeviceCode() + " is reporting above its permitted rate");
            return Decision.HALT;
        }

        context.note("deviceCode", device.getDeviceCode());
        context.note("resolutionStrategy", DeviceResolver.strategyOf(context));
        return Decision.CONTINUE;
    }

    @Override
    public int getOrder() {
        return Stages.DEVICE_RESOLUTION;
    }
}
