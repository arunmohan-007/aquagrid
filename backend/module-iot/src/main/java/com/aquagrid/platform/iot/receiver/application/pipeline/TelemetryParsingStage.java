package com.aquagrid.platform.iot.receiver.application.pipeline;

import com.aquagrid.platform.common.error.ErrorCode;
import com.aquagrid.platform.iot.receiver.application.parser.PayloadParserRegistry;
import com.aquagrid.platform.iot.receiver.domain.model.ParsedTelemetry;
import com.aquagrid.platform.iot.receiver.domain.model.ReceptionContext;
import com.aquagrid.platform.iot.receiver.spi.PayloadParser;
import com.aquagrid.platform.iot.receiver.spi.ReceiverStage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Decodes the payload into readings.
 *
 * <p>Selects a parser by encoding, never by transport — see {@link PayloadParserRegistry} — and
 * records which one ran on the packet log, so a decode fault can be reproduced from the stored
 * bytes by pointing the same parser at them.
 *
 * <p>A parser that throws is treated as a malformed payload rather than as a fault, with one
 * exception noted below. That is the right default: the overwhelming majority of decode failures
 * are a device sending something the platform does not understand, which is traffic, not an
 * incident. The exception is a parser throwing something other than its declared exception, which
 * means the parser itself is broken — and that packet is recoverable once the parser is fixed, so
 * it is dead-lettered rather than discarded.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelemetryParsingStage implements ReceiverStage {

    private final PayloadParserRegistry parsers;

    @Override
    public String name() {
        return "PARSING";
    }

    @Override
    public Decision execute(ReceptionContext context) {
        Optional<PayloadParser> parser = parsers.select(context);
        if (parser.isEmpty()) {
            context.reject(ErrorCode.RECEIVER_MALFORMED_PAYLOAD,
                    "No parser recognised this payload");
            return Decision.HALT;
        }

        try {
            ParsedTelemetry telemetry = parser.get().parse(context);
            context.parsed(telemetry);
            context.note("parser", telemetry.parser());
            return Decision.CONTINUE;
        } catch (PayloadParser.PayloadParseException e) {
            context.reject(ErrorCode.RECEIVER_MALFORMED_PAYLOAD, e.getMessage());
            return Decision.HALT;
        } catch (RuntimeException e) {
            // The parser is defective, not the packet. Classified as INTERNAL_ERROR so the
            // dispatch path dead-letters it: once the parser is fixed the stored bytes can be
            // replayed and the reading recovered, which would be impossible had this been recorded
            // as a malformed payload and discarded.
            log.error("Parser {} failed on packet {}", parser.get().name(), context.packetId(), e);
            context.reject(ErrorCode.INTERNAL_ERROR,
                    "Parser " + parser.get().name() + " failed unexpectedly");
            return Decision.HALT;
        }
    }

    @Override
    public int getOrder() {
        return Stages.PARSING;
    }
}
