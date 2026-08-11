package com.aquagrid.platform.iot.application.export;

import java.time.Instant;
import java.util.List;

/**
 * The shape of a readings export, independent of the file it is written to.
 *
 * <p>Both writers consume these types and neither knows what the other produces. That is what stops
 * the spreadsheet and the PDF drifting into showing different columns, different units or different
 * row counts for the same query — the two most likely places a report loses trust, because a
 * discrepancy between two exports of the same data is indistinguishable to the reader from a
 * discrepancy in the data.
 */
public final class ReadingExport {

    private ReadingExport() {
    }

    /** What the report was asked for, rendered on the cover of every export. */
    public record Criteria(
            String organizationName,
            Instant from,
            Instant to,
            String deviceCode,
            String deviceType,
            String transport,
            String metric,
            Instant generatedAt,
            String generatedBy
    ) {
        /**
         * The filters as a human sentence, for the report header.
         *
         * <p>Printed on the export rather than left in the browser's filter bar, because the file
         * outlives the screen: a spreadsheet emailed to a regulator has to say what it contains, and
         * "all devices" and "one district metered area" produce the same-looking table.
         */
        public String describe() {
            List<String> parts = new java.util.ArrayList<>();
            parts.add(deviceCode != null ? "Device " + deviceCode : "All devices");
            if (deviceType != null) {
                parts.add("type " + deviceType);
            }
            if (transport != null) {
                parts.add("network " + transport);
            }
            parts.add(metric != null ? "metric " + metric : "all metrics");
            return String.join(" · ", parts);
        }
    }

    /**
     * One exported reading.
     *
     * <p>Carries the device's identity on every row rather than grouping by device, because a
     * timestamped export is filtered and sorted by whoever receives it — and a row that only means
     * something in the context of a heading two hundred rows above it stops meaning anything the
     * moment someone sorts the sheet by value.
     */
    public record Row(
            Instant observedAt,
            Instant receivedAt,
            String deviceCode,
            String deviceName,
            String deviceType,
            String transport,
            String deviceSource,
            String metric,
            String metricLabel,
            Double value,
            String unit
    ) {
    }

    /** The columns, in order, shared by every format. */
    public static final List<String> COLUMNS = List.of(
            "Observed at (UTC)",
            "Received at (UTC)",
            "Device",
            "Name",
            "Device type",
            "Network",
            "Source",
            "Reading",
            "Value",
            "Unit");

    /** Extracts one row's cells in column order, so the two writers cannot disagree on layout. */
    public static List<String> cells(Row row, java.time.format.DateTimeFormatter timestamps) {
        return List.of(
                row.observedAt() == null ? "" : timestamps.format(row.observedAt()),
                row.receivedAt() == null ? "" : timestamps.format(row.receivedAt()),
                nullToEmpty(row.deviceCode()),
                nullToEmpty(row.deviceName()),
                nullToEmpty(row.deviceType()),
                nullToEmpty(row.transport()),
                nullToEmpty(row.deviceSource()),
                nullToEmpty(row.metricLabel()),
                row.value() == null ? "" : trimNumber(row.value()),
                nullToEmpty(row.unit()));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * Renders a value without inventing precision or losing it.
     *
     * <p>A cumulative register runs to six significant figures and a flow rate to two decimals, so a
     * fixed format would either truncate the first or pad the second with meaningless zeros.
     */
    private static String trimNumber(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return String.valueOf((long) value);
        }
        return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
