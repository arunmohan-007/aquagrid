package com.aquagrid.platform.iot.receiver.application.parser;

import com.aquagrid.platform.iot.receiver.domain.model.ParsedTelemetry;
import com.aquagrid.platform.iot.receiver.domain.model.ReceptionContext;
import com.aquagrid.platform.iot.receiver.spi.PayloadParser;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * Template for payload parsers: the shape every parser shares, once.
 *
 * <p>{@link #parse} is final and fixed — reject an empty payload, delegate to the subclass, never
 * return null. Subclasses supply only {@link #doParse}, the part that is actually different. That
 * is the Template Method pattern doing its real job here: three behaviours that every parser must
 * get right and that were previously copied into each adapter (empty-payload handling, keep-alive
 * semantics, exception wrapping) now cannot be got wrong by a new one.
 *
 * <p>The timestamp helpers matter more than they look. Devices report time in at least four
 * shapes, and a parser that mishandles one does not fail loudly — it silently substitutes the
 * server's clock, and the reading lands with a plausible-looking timestamp that is wrong by however
 * long the device was buffering. That is invisible until someone tries to reconcile a consumption
 * series.
 */
public abstract class AbstractPayloadParser implements PayloadParser {

    @Override
    public final ParsedTelemetry parse(ReceptionContext context) throws PayloadParseException {
        if (context.getPacket().size() == 0) {
            // A zero-byte packet is a device saying "I am here". Every transport produces them —
            // TCP keep-alives, empty LoRaWAN uplinks — and they are the only evidence that a
            // device on a long reporting interval is alive at all.
            return ParsedTelemetry.keepAlive(name());
        }
        ParsedTelemetry parsed = doParse(context);
        return parsed == null ? ParsedTelemetry.keepAlive(name()) : parsed;
    }

    /** The parser-specific decode. Called only with a non-empty payload. */
    protected abstract ParsedTelemetry doParse(ReceptionContext context) throws PayloadParseException;

    // ---- Shared helpers ---------------------------------------------------------------------

    /**
     * Reads a timestamp in any of the forms devices actually emit: ISO-8601, epoch seconds, or
     * epoch milliseconds.
     *
     * <p>Seconds and milliseconds are told apart by magnitude — a value above 10^11 cannot be a
     * plausible epoch-second reading (it would be the year 5138) and is therefore milliseconds.
     * Guessing wrong puts a reading fifty years out, where it is far enough from every retention
     * window to be effectively deleted.
     *
     * @return the parsed instant, or null when the field is absent or unreadable. Null is the
     *         honest answer and the caller substitutes reception time <em>and records that it did</em>
     */
    protected static Instant parseTimestamp(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        try {
            return Instant.parse(trimmed);
        } catch (DateTimeParseException notIso) {
            // fall through to numeric forms
        }
        try {
            long numeric = Long.parseLong(trimmed);
            return numeric > 100_000_000_000L
                    ? Instant.ofEpochMilli(numeric)
                    : Instant.ofEpochSecond(numeric);
        } catch (NumberFormatException notNumeric) {
            return null;
        }
    }

    /** First readable timestamp among several candidate field names. */
    protected static Instant firstTimestamp(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isNumber()) {
                Instant parsed = parseTimestamp(value.asText());
                if (parsed != null) {
                    return parsed;
                }
            }
            if (value.isTextual()) {
                Instant parsed = parseTimestamp(value.asText());
                if (parsed != null) {
                    return parsed;
                }
            }
        }
        return null;
    }

    /** A numeric field, or null. Deliberately not 0: absent and zero are different readings. */
    protected static Double number(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isNumber()) {
                return value.asDouble();
            }
            // Vendors that quote every value as a string are common enough to be worth handling;
            // rejecting them would mean an integration that works everywhere but one carrier.
            if (value.isTextual()) {
                try {
                    return Double.parseDouble(value.asText().trim());
                } catch (NumberFormatException ignored) {
                    // not a number in disguise
                }
            }
        }
        return null;
    }

    protected static String text(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isTextual() && !value.asText().isBlank()) {
                return value.asText().trim();
            }
        }
        return null;
    }

    protected static Integer integer(JsonNode node, String... fields) {
        Double value = number(node, fields);
        return value == null ? null : value.intValue();
    }

    @Override
    public int getOrder() {
        return 100;
    }
}
