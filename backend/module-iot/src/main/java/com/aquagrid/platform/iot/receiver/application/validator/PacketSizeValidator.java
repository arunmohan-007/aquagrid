package com.aquagrid.platform.iot.receiver.application.validator;

import com.aquagrid.platform.common.error.ErrorCode;
import com.aquagrid.platform.iot.receiver.domain.model.ReceptionContext;
import com.aquagrid.platform.iot.receiver.domain.model.Rejection;
import com.aquagrid.platform.iot.receiver.infrastructure.config.ReceiverProperties;
import com.aquagrid.platform.iot.receiver.spi.PayloadValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Refuses packets larger than the configured ceiling.
 *
 * <p>First validator, and first for a reason that outranks tidiness: it runs before any parser
 * touches the bytes. Telemetry payloads are tens to hundreds of bytes; anything approaching the
 * limit is a misconfiguration or an attempt to make the receiver do expensive work on attacker-
 * chosen input. Checking size after decoding would mean the decode has already happened.
 *
 * <p>The transports enforce their own frame limits too, before a packet is even constructed — the
 * socket servers cannot afford to buffer an unbounded frame in the first place. This is the
 * backstop that applies uniformly, including to transports whose framing is someone else's.
 */
@Component
@RequiredArgsConstructor
public class PacketSizeValidator implements PayloadValidator {

    public static final int ORDER = 10;

    private final ReceiverProperties properties;

    @Override
    public String name() {
        return "PACKET_SIZE";
    }

    @Override
    public Optional<Rejection> validate(ReceptionContext context) {
        int size = context.getPacket().size();
        int limit = properties.limits().maxPacketBytes();
        if (size > limit) {
            return Optional.of(Rejection.of(ErrorCode.RECEIVER_PAYLOAD_TOO_LARGE,
                    "Payload of " + size + " bytes exceeds the limit of " + limit));
        }
        return Optional.empty();
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
