package com.aquagrid.platform.iot.receiver.application.parser;

import com.aquagrid.platform.iot.api.DeviceMessage;
import com.aquagrid.platform.iot.receiver.api.TelemetryEvent;
import com.aquagrid.platform.iot.receiver.domain.model.ParsedTelemetry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The meter frame layout, verified against known bytes.
 *
 * <p>Worth testing exhaustively despite being sixty lines of arithmetic, because every failure mode
 * here is silent. A byte order read the wrong way round does not throw — it produces a volume of
 * 16,777,216 litres instead of 1, which is a plausible-looking number that flows into a bill. A
 * scaling factor applied twice produces a reading a tenth of the truth, which nobody notices until
 * a reconciliation. None of it is caught by an integration test that only asserts a reading arrived.
 */
class WaterMeterFrameCodecTest {

    @Nested
    @DisplayName("field decoding")
    class FieldDecoding {

        @Test
        @DisplayName("reads volume as a little-endian uint32 in decilitres")
        void readsVolume() {
            // 0x000003E8 = 1000 decilitres = 100 L, little-endian on the wire.
            byte[] frame = {(byte) 0xE8, 0x03, 0x00, 0x00};

            ParsedTelemetry decoded = WaterMeterFrameCodec.decode(frame, "test");

            assertThat(decoded.metrics()).containsEntry(DeviceMessage.Metrics.VOLUME, 100.0);
        }

        @Test
        @DisplayName("does not sign-extend a high volume byte")
        void readsHighVolumeWithoutSignExtension() {
            // Every byte set: 4,294,967,295 dL. A signed read gives -1, and a meter reading minus
            // one tenth of a litre would sail through any range check that only guards the top end.
            byte[] frame = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};

            ParsedTelemetry decoded = WaterMeterFrameCodec.decode(frame, "test");

            assertThat(decoded.metrics().get(DeviceMessage.Metrics.VOLUME))
                    .isEqualTo(4_294_967_295L / 10.0);
        }

        @Test
        @DisplayName("reads flow rate as a little-endian uint16 in centilitres per minute")
        void readsFlowRate() {
            // 0x0064 = 100 cL/min = 1 L/min.
            byte[] frame = {0, 0, 0, 0, 0x64, 0x00};

            ParsedTelemetry decoded = WaterMeterFrameCodec.decode(frame, "test");

            assertThat(decoded.metrics()).containsEntry(DeviceMessage.Metrics.FLOW_RATE, 1.0);
        }

        @Test
        @DisplayName("reads battery in decivolts and surfaces it as a first-class field")
        void readsBattery() {
            byte[] frame = {0, 0, 0, 0, 0, 0, 36}; // 3.6 V

            ParsedTelemetry decoded = WaterMeterFrameCodec.decode(frame, "test");

            assertThat(decoded.battery()).isEqualTo(3.6);
            assertThat(decoded.metrics()).containsEntry(DeviceMessage.Metrics.BATTERY_VOLTAGE, 3.6);
        }

        @Test
        @DisplayName("reads pressure in millibar when the frame carries a sensor")
        void readsPressure() {
            byte[] frame = {0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xB8, 0x0B}; // 3000 mbar = 3 bar

            ParsedTelemetry decoded = WaterMeterFrameCodec.decode(frame, "test");

            assertThat(decoded.metrics()).containsEntry(DeviceMessage.Metrics.PRESSURE, 3.0);
        }
    }

    @Nested
    @DisplayName("status flags")
    class StatusFlags {

        @Test
        @DisplayName("raises each flag from its own bit and mirrors it as a 0/1 metric")
        void decodesFlags() {
            byte[] frame = {0, 0, 0, 0, 0, 0, 0, 0x07}; // tamper | reverse flow | leak

            ParsedTelemetry decoded = WaterMeterFrameCodec.decode(frame, "test");

            assertThat(decoded.alarmFlags()).containsExactlyInAnyOrder(
                    TelemetryEvent.Flags.TAMPER,
                    TelemetryEvent.Flags.REVERSE_FLOW,
                    TelemetryEvent.Flags.LEAK);
            // Mirrored, because alarms read the flag set and the readings table stores metrics.
            // Deriving one from the other later is how the two views come to disagree.
            assertThat(decoded.metrics())
                    .containsEntry(DeviceMessage.Metrics.TAMPER, 1.0)
                    .containsEntry(DeviceMessage.Metrics.REVERSE_FLOW, 1.0)
                    .containsEntry(DeviceMessage.Metrics.LEAK, 1.0);
        }

        @Test
        @DisplayName("records a cleared flag as 0 rather than omitting it")
        void recordsClearedFlags() {
            byte[] frame = {0, 0, 0, 0, 0, 0, 0, 0x00};

            ParsedTelemetry decoded = WaterMeterFrameCodec.decode(frame, "test");

            // An absent metric and a metric reading zero are different claims: the first says the
            // meter did not report, the second says it reported no tamper. An alarm that clears
            // depends on the difference.
            assertThat(decoded.alarmFlags()).isEmpty();
            assertThat(decoded.metrics()).containsEntry(DeviceMessage.Metrics.TAMPER, 0.0);
        }
    }

    @Nested
    @DisplayName("truncated frames")
    class TruncatedFrames {

        @Test
        @DisplayName("decodes as far as the bytes go rather than rejecting")
        void decodesPartialFrame() {
            byte[] volumeOnly = {(byte) 0xE8, 0x03, 0x00, 0x00};

            ParsedTelemetry decoded = WaterMeterFrameCodec.decode(volumeOnly, "test");

            // The cheapest devices in a fleet send exactly this. Treating truncation as corruption
            // would strand them.
            assertThat(decoded.metrics()).containsOnlyKeys(DeviceMessage.Metrics.VOLUME);
        }

        @Test
        @DisplayName("returns an empty reading for a frame too short to hold anything")
        void handlesRunt() {
            ParsedTelemetry decoded = WaterMeterFrameCodec.decode(new byte[]{0x01}, "test");

            assertThat(decoded.metrics()).isEmpty();
            assertThat(decoded.alarmFlags()).isEmpty();
        }
    }

    @Nested
    @DisplayName("text-encoded payloads")
    class TextEncoding {

        @Test
        @DisplayName("decodes hex")
        void decodesHex() {
            assertThat(WaterMeterFrameCodec.decodeText("E8030000"))
                    .containsExactly((byte) 0xE8, 0x03, 0x00, 0x00);
        }

        @Test
        @DisplayName("decodes base64")
        void decodesBase64() {
            byte[] frame = {(byte) 0xE8, 0x03, 0x00, 0x00};

            assertThat(WaterMeterFrameCodec.decodeText(Base64.getEncoder().encodeToString(frame)))
                    .containsExactly(frame);
        }

        @Test
        @DisplayName("prefers hex, because every hex string is also valid base64 input")
        void prefersHexOverBase64() {
            // "12345678" is valid in both encodings and means completely different bytes in each.
            // Testing base64 first would silently mis-decode every hex payload in the estate.
            assertThat(WaterMeterFrameCodec.decodeText("12345678"))
                    .containsExactly(0x12, 0x34, 0x56, 0x78);
        }

        @Test
        @DisplayName("returns empty for text that is neither encoding")
        void handlesUndecodableText() {
            assertThat(WaterMeterFrameCodec.decodeText("not a payload!")).isEmpty();
            assertThat(WaterMeterFrameCodec.decodeText(null)).isEmpty();
        }

        @Test
        @DisplayName("round-trips a full frame through hex")
        void roundTripsFullFrame() {
            // 100 L cumulative, 1 L/min, 3.6 V, tamper set.
            String hex = "E8030000640024" + "01";

            ParsedTelemetry decoded = WaterMeterFrameCodec.decode(
                    WaterMeterFrameCodec.decodeText(hex), "test");

            assertThat(decoded.metrics())
                    .containsEntry(DeviceMessage.Metrics.VOLUME, 100.0)
                    .containsEntry(DeviceMessage.Metrics.FLOW_RATE, 1.0)
                    .containsEntry(DeviceMessage.Metrics.BATTERY_VOLTAGE, 3.6);
            assertThat(decoded.alarmFlags()).contains(TelemetryEvent.Flags.TAMPER);
        }

        @Test
        @DisplayName("never parses an odd-length string as hex")
        void rejectsOddLengthHex() {
            // Guards the substring arithmetic: parsing an odd-length string pairwise reads past the
            // end of it. The length check in isHex is what stops that, and this is what pins it.
            //
            // "ABC" then falls through to base64, which accepts unpadded input and yields two
            // bytes. That is the documented fallback rather than a defect — but it is the reason a
            // truncated hex payload decodes to nonsense instead of failing loudly, so the frame
            // decoder must (and does) tolerate whatever comes back.
            byte[] decoded = WaterMeterFrameCodec.decodeText("ABC");

            assertThat(decoded).hasSize(2);
            assertThat(WaterMeterFrameCodec.decode(decoded, "test").metrics()).isEmpty();
        }

        @Test
        @DisplayName("returns empty when a string is neither hex nor base64")
        void rejectsUndecodable() {
            assertThat(WaterMeterFrameCodec.decodeText("zz!!")).isEmpty();
        }
    }

    /**
     * The encoder exists so the fleet simulator can produce the bytes a physical meter produces.
     * Its correctness is only meaningful relative to the decoder, so every test here is a round
     * trip: an encoder that agreed with itself but not with {@code decode} would generate a fleet
     * whose every reading was wrong in a way no assertion about the encoder alone would catch.
     */
    @Nested
    @DisplayName("encoding")
    class Encoding {

        @Test
        @DisplayName("round-trips volume, flow and battery through the decoder")
        void roundTripsFields() {
            byte[] frame = WaterMeterFrameCodec.encode(1234.5, 7.25, 3.6,
                    false, false, false, false);

            ParsedTelemetry decoded = WaterMeterFrameCodec.decode(frame, "test");

            assertThat(decoded.metrics())
                    .containsEntry(DeviceMessage.Metrics.VOLUME, 1234.5)
                    .containsEntry(DeviceMessage.Metrics.FLOW_RATE, 7.25)
                    .containsEntry(DeviceMessage.Metrics.BATTERY_VOLTAGE, 3.6);
        }

        @Test
        @DisplayName("writes little-endian, matching what the decoder reads")
        void writesLittleEndian() {
            // The same 100 L the decoding tests assert on, produced rather than hand-written. If
            // either side flipped its byte order this is the assertion that fails.
            byte[] frame = WaterMeterFrameCodec.encode(100, 0, 0, false, false, false, false);

            assertThat(frame[0]).isEqualTo((byte) 0xE8);
            assertThat(frame[1]).isEqualTo((byte) 0x03);
            assertThat(frame[2]).isZero();
            assertThat(frame[3]).isZero();
        }

        @Test
        @DisplayName("carries flow direction in the status byte, not as a negative field")
        void encodesReverseFlowAsAFlag() {
            // The wire field is unsigned. A reverse-flowing meter reports a positive magnitude and
            // a set bit — which is what the physical device does, and what the alarm engine reads.
            byte[] frame = WaterMeterFrameCodec.encode(500, -3.5, 3.6,
                    false, true, false, false);

            ParsedTelemetry decoded = WaterMeterFrameCodec.decode(frame, "test");

            assertThat(decoded.metrics()).containsEntry(DeviceMessage.Metrics.FLOW_RATE, 3.5);
            assertThat(decoded.alarmFlags()).contains(TelemetryEvent.Flags.REVERSE_FLOW);
        }

        @Test
        @DisplayName("sets each flag in its own bit")
        void encodesEachFlag() {
            ParsedTelemetry decoded = WaterMeterFrameCodec.decode(
                    WaterMeterFrameCodec.encode(0, 0, 0, true, true, true, true), "test");

            assertThat(decoded.alarmFlags()).containsExactlyInAnyOrder(
                    TelemetryEvent.Flags.TAMPER,
                    TelemetryEvent.Flags.REVERSE_FLOW,
                    TelemetryEvent.Flags.LEAK,
                    TelemetryEvent.Flags.BURST);
        }

        @Test
        @DisplayName("clamps to the field width instead of wrapping")
        void clampsOverflow() {
            // A register beyond uint32. Wrapping would produce a small number and read as a meter
            // rollover — a plausible value that silently discards nine tenths of the consumption.
            byte[] frame = WaterMeterFrameCodec.encode(1e12, 1e6, 99,
                    false, false, false, false);

            ParsedTelemetry decoded = WaterMeterFrameCodec.decode(frame, "test");

            assertThat(decoded.metrics().get(DeviceMessage.Metrics.VOLUME))
                    .isEqualTo(4_294_967_295L / 10.0);
            assertThat(decoded.metrics().get(DeviceMessage.Metrics.FLOW_RATE))
                    .isEqualTo(65_535 / 100.0);
        }

        @Test
        @DisplayName("never emits a negative register")
        void clampsNegativeVolume() {
            ParsedTelemetry decoded = WaterMeterFrameCodec.decode(
                    WaterMeterFrameCodec.encode(-5, 0, 3.6, false, false, false, false), "test");

            assertThat(decoded.metrics()).containsEntry(DeviceMessage.Metrics.VOLUME, 0.0);
        }
    }

    @Test
    @DisplayName("never mutates the caller's array")
    void doesNotMutateInput() {
        byte[] frame = "E8030000".getBytes(StandardCharsets.US_ASCII);
        byte[] original = frame.clone();

        WaterMeterFrameCodec.decode(frame, "test");

        // Parsers run in sequence over one packet; a decoder that unpacked in place would corrupt
        // the payload the packet log is about to store.
        assertThat(frame).containsExactly(original);
    }
}
