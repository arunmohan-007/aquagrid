package com.aquagrid.platform.iot.receiver.application.parser;

import com.aquagrid.platform.iot.api.DeviceMessage;
import com.aquagrid.platform.iot.receiver.api.TelemetryEvent;
import com.aquagrid.platform.iot.receiver.domain.model.ParsedTelemetry;

import java.util.Base64;
import java.util.HexFormat;

/**
 * The binary layout common smart water meters use, decoded once.
 *
 * <p>Extracted rather than reimplemented per transport. The same frame reaches the platform over
 * LoRaWAN (base64 in a network-server envelope), over TCP and UDP (raw bytes on a socket) and
 * occasionally over HTTP (hex in a JSON field) — it is the meter's format, not the carrier's. Three
 * copies of this arithmetic would be three places for an endianness mistake to hide, and only one
 * of them would be exercised by any given deployment's tests.
 *
 * <p>Layout, little-endian throughout:
 * <pre>
 *   bytes 0-3 : cumulative volume, uint32, decilitres
 *   bytes 4-5 : flow rate, uint16, centilitres/min
 *   byte  6   : battery voltage, decivolts
 *   byte  7   : status flags — bit0 tamper, bit1 reverse flow, bit2 leak, bit3 burst
 *   bytes 8-9 : pressure, uint16, millibar (optional; absent on meters without a sensor)
 * </pre>
 *
 * <p>Short frames decode as far as they go. A meter that sends four bytes has reported its volume
 * and nothing else, which is a valid uplink — treating truncation as corruption would reject the
 * cheapest devices in the fleet.
 */
public final class WaterMeterFrameCodec {

    private WaterMeterFrameCodec() {
    }

    /**
     * Builds a frame in the layout above.
     *
     * <p>The inverse of {@link #decode}, and it lives here rather than in the simulator that calls
     * it for the reason the class exists at all: an encoder written elsewhere would be a second
     * statement of the layout, free to disagree with this one about endianness or scaling. Kept
     * adjacent, a change to the layout is a change to one file and the round trip is testable in a
     * single assertion.
     *
     * <p>Values are clamped to their field widths rather than rejected. Overflow here means the
     * caller asked for a reading the wire format cannot carry, which is exactly what a real meter
     * does with a register rollover — and throwing would turn a representable-range question into a
     * failed uplink.
     *
     * @param volumeLitres cumulative register, litres
     * @param flowLpm      instantaneous flow, litres per minute. Negative flow is a reverse-flow
     *                     condition and is carried in the status byte, not the (unsigned) field
     * @param batteryVolts cell voltage
     * @param tamper       tamper flag — bit 0
     * @param reverseFlow  reverse-flow flag — bit 1
     * @param leak         leak flag — bit 2
     * @param burst        burst flag — bit 3
     */
    public static byte[] encode(double volumeLitres, double flowLpm, double batteryVolts,
                                boolean tamper, boolean reverseFlow, boolean leak, boolean burst) {
        byte[] frame = new byte[8];

        long volumeDecilitres = clamp(Math.round(volumeLitres * 10.0), 0xFFFFFFFFL);
        frame[0] = (byte) (volumeDecilitres);
        frame[1] = (byte) (volumeDecilitres >>> 8);
        frame[2] = (byte) (volumeDecilitres >>> 16);
        frame[3] = (byte) (volumeDecilitres >>> 24);

        // The flow field is unsigned, so direction travels in the status byte. Decoding therefore
        // yields a positive rate plus a reverse-flow flag, which is what a real meter reports —
        // the sign lives in the condition, not in the magnitude.
        long flowCentilitres = clamp(Math.round(Math.abs(flowLpm) * 100.0), 0xFFFFL);
        frame[4] = (byte) (flowCentilitres);
        frame[5] = (byte) (flowCentilitres >>> 8);

        frame[6] = (byte) clamp(Math.round(batteryVolts * 10.0), 0xFFL);

        int flags = 0;
        if (tamper) flags |= 0x01;
        if (reverseFlow) flags |= 0x02;
        if (leak) flags |= 0x04;
        if (burst) flags |= 0x08;
        frame[7] = (byte) flags;

        return frame;
    }

    private static long clamp(long value, long max) {
        return Math.clamp(value, 0L, max);
    }

    /** Decodes a frame into readings and flags. Never throws: a frame too short is simply emptier. */
    public static ParsedTelemetry decode(byte[] frame, String parserName) {
        ParsedTelemetry.Accumulator readings = new ParsedTelemetry.Accumulator();
        readings.raw("frameBytes", frame.length);

        Double battery = null;
        if (frame.length >= 4) {
            long volumeDecilitres = u32(frame, 0);
            readings.metric(DeviceMessage.Metrics.VOLUME, volumeDecilitres / 10.0);
        }
        if (frame.length >= 6) {
            int flowCentilitres = u16(frame, 4);
            readings.metric(DeviceMessage.Metrics.FLOW_RATE, flowCentilitres / 100.0);
        }
        if (frame.length >= 7) {
            battery = (frame[6] & 0xFF) / 10.0;
            readings.metric(DeviceMessage.Metrics.BATTERY_VOLTAGE, battery);
        }
        if (frame.length >= 8) {
            int flags = frame[7] & 0xFF;
            // Recorded as both a flag and a 0/1 metric: the flag is what alarms consume, the metric
            // is what the readings table stores, and deriving one from the other later would mean
            // two views of the same bit that can disagree.
            readings.flag(TelemetryEvent.Flags.TAMPER, (flags & 0x01) != 0,
                    DeviceMessage.Metrics.TAMPER);
            readings.flag(TelemetryEvent.Flags.REVERSE_FLOW, (flags & 0x02) != 0,
                    DeviceMessage.Metrics.REVERSE_FLOW);
            readings.flag(TelemetryEvent.Flags.LEAK, (flags & 0x04) != 0,
                    DeviceMessage.Metrics.LEAK);
            readings.flag(TelemetryEvent.Flags.BURST, (flags & 0x08) != 0, null);
        }
        if (frame.length >= 10) {
            readings.metric(DeviceMessage.Metrics.PRESSURE, u16(frame, 8) / 1000.0);
        }

        return ParsedTelemetry.builder()
                .metrics(readings.metrics())
                .alarmFlags(readings.flags())
                .battery(battery)
                .raw(readings.raw())
                .parser(parserName)
                .build();
    }

    /**
     * Decodes a payload that arrived as text, accepting hex or base64.
     *
     * <p>Both are in the wild for the same meter — a LoRaWAN network server base64s the frame, a
     * REST bridge usually hex-encodes it — and which one a deployment sees is not something the
     * parser should have to be configured with. Hex is tried first because it is unambiguous:
     * every valid hex string is also valid base64 input, so testing base64 first would silently
     * mis-decode hex payloads into garbage.
     */
    public static byte[] decodeText(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return new byte[0];
        }
        String value = encoded.trim();
        if (isHex(value)) {
            return HexFormat.of().parseHex(value);
        }
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException notBase64) {
            return new byte[0];
        }
    }

    private static boolean isHex(String value) {
        if (value.isEmpty() || value.length() % 2 != 0) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.digit(value.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private static long u32(byte[] bytes, int offset) {
        return ((bytes[offset + 3] & 0xFFL) << 24)
                | ((bytes[offset + 2] & 0xFFL) << 16)
                | ((bytes[offset + 1] & 0xFFL) << 8)
                | (bytes[offset] & 0xFFL);
    }

    private static int u16(byte[] bytes, int offset) {
        return ((bytes[offset + 1] & 0xFF) << 8) | (bytes[offset] & 0xFF);
    }
}
