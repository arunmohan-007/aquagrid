package com.aquagrid.platform.iot.receiver.spi;

import com.aquagrid.platform.iot.receiver.domain.model.ParsedTelemetry;
import com.aquagrid.platform.iot.receiver.domain.model.ReceptionContext;
import org.springframework.core.Ordered;

/**
 * Turns a payload into readings.
 *
 * <p>One parser per payload encoding, selected by {@link #supports} rather than by a switch on the
 * transport — because the two do not correspond. ChirpStack delivers LoRaWAN over an HTTP webhook
 * and MQTT alike; a cellular logger may post JSON or push the same binary frame it would over TCP.
 * Binding the parser to the encoding rather than the carrier is what stops "add a transport" from
 * meaning "duplicate a codec".
 *
 * <p>Parsers are selected in {@link Ordered} order, most specific first, and the winner is recorded
 * on the packet log. A parser must be a pure function of the packet: no repositories, no clock
 * beyond the packet's own, no mutation of the payload. That is what makes a decode fault
 * reproducible from the stored bytes alone, which is the entire value of keeping them.
 *
 * @see com.aquagrid.platform.iot.receiver.application.parser.AbstractPayloadParser
 */
public interface PayloadParser extends Ordered {

    /** Parser name, recorded on the packet log and on the parsed telemetry. */
    String name();

    /**
     * Whether this parser understands the packet's encoding. Consults the packet's content type,
     * its shape and — where the device is already resolved — its communication profile.
     */
    boolean supports(ReceptionContext context);

    /**
     * Decodes the payload.
     *
     * @throws PayloadParseException when the bytes are not what {@link #supports} took them for.
     *         A payload that is well-formed but says nothing is not an error: return
     *         {@link ParsedTelemetry#keepAlive} — devices report in with nothing to say, and
     *         treating that as a failure loses the only evidence that they are alive
     */
    ParsedTelemetry parse(ReceptionContext context) throws PayloadParseException;

    /** Thrown when a payload cannot be decoded. Carries no stack trace: it is expected traffic. */
    class PayloadParseException extends Exception {

        public PayloadParseException(String message) {
            super(message, null, false, false);
        }

        public PayloadParseException(String message, Throwable cause) {
            super(message, cause, false, false);
        }
    }
}
