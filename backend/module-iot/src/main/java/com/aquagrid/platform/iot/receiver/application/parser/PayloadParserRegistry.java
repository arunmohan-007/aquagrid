package com.aquagrid.platform.iot.receiver.application.parser;

import com.aquagrid.platform.iot.receiver.domain.model.ReceptionContext;
import com.aquagrid.platform.iot.receiver.spi.PayloadParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Chooses the parser for a payload.
 *
 * <p>First supporting parser in order wins — most specific first, general JSON last. There is no
 * switch on transport or on communication profile anywhere in the selection: each parser answers
 * for itself whether it understands the bytes, which is what allows one encoding to be shared
 * across transports (a meter frame arrives over LoRaWAN, TCP and UDP alike) and one transport to
 * carry several encodings (HTTP delivers ChirpStack envelopes and plain JSON both).
 *
 * <p>Adding a codec — a vendor's proprietary frame, Modbus registers — is one bean with an order.
 * Nothing here changes.
 */
@Slf4j
@Service
public class PayloadParserRegistry {

    private final List<PayloadParser> parsers;

    public PayloadParserRegistry(List<PayloadParser> parsers) {
        List<PayloadParser> ordered = new ArrayList<>(parsers);
        ordered.sort(AnnotationAwareOrderComparator.INSTANCE);
        this.parsers = List.copyOf(ordered);
        log.info("Payload parser chain: {}", this.parsers.stream().map(PayloadParser::name).toList());
    }

    /**
     * @return the parser that claims the payload, or empty when none does — which the caller turns
     *         into {@code RECEIVER_MALFORMED_PAYLOAD}. Empty is rare by design: the JSON parser
     *         claims anything that parses as an object and the binary parser claims anything from a
     *         socket transport, so reaching empty means the payload is genuinely unrecognisable
     */
    public Optional<PayloadParser> select(ReceptionContext context) {
        for (PayloadParser parser : parsers) {
            try {
                if (parser.supports(context)) {
                    return Optional.of(parser);
                }
            } catch (RuntimeException e) {
                // A parser that throws while merely inspecting a payload is defective, but a
                // defective parser must not take down ingestion for the transports that do not use
                // it. Skip it, log it, carry on.
                log.warn("Parser {} threw during supports() — skipping it for packet {}",
                        parser.name(), context.packetId(), e);
            }
        }
        return Optional.empty();
    }

    public List<String> parserNames() {
        return parsers.stream().map(PayloadParser::name).toList();
    }
}
