package com.aquagrid.platform.iot.application.service;

import com.aquagrid.platform.common.error.BusinessException;
import com.aquagrid.platform.common.error.ErrorCode;
import com.aquagrid.platform.iot.application.export.ReadingExport;
import com.aquagrid.platform.iot.application.export.ReadingPdfWriter;
import com.aquagrid.platform.iot.application.export.ReadingWorkbookWriter;
import com.aquagrid.platform.iot.domain.model.Device;
import com.aquagrid.platform.iot.domain.model.DeviceReading;
import com.aquagrid.platform.iot.domain.model.MetricCatalog;
import com.aquagrid.platform.iot.infrastructure.persistence.DeviceReadingRepository;
import com.aquagrid.platform.iot.infrastructure.persistence.DeviceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Exports timestamped readings as a spreadsheet or a PDF.
 *
 * <p>Both formats come from one query and one row model, so a spreadsheet and a PDF of the same
 * request cannot disagree. That is the property worth protecting: a discrepancy between two exports
 * of the same data is indistinguishable, to whoever receives them, from a discrepancy in the data.
 *
 * <p><b>Bounded twice, deliberately.</b> The device set is resolved first and capped, then readings
 * are paged rather than loaded whole. Without both, a request for "all devices, last year" is a
 * heap-exhaustion primitive available to anyone with permission to run a report — and reporting
 * permissions are handed out far more freely than administrative ones.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReadingExportService {

    /**
     * Rows per format.
     *
     * <p>The spreadsheet's cap is high because it streams and because a spreadsheet is a working
     * artefact — it gets filtered and pivoted, so more rows are more useful. The PDF's is far lower
     * because it is a document: it is materialised in memory to be paginated, and beyond a few
     * thousand rows nobody reads it, they grep the spreadsheet instead.
     */
    private static final int MAX_XLSX_ROWS = 200_000;
    private static final int MAX_PDF_ROWS = 20_000;

    /** Readings fetched per round trip. Bounds memory without paying a query per hundred rows. */
    private static final int PAGE_SIZE = 2_000;

    /** Devices one report may span. Beyond this the filters are not a report, they are a dump. */
    private static final int MAX_DEVICES = 5_000;

    /** Widest window a single export may cover, whatever the filters. */
    private static final Duration MAX_WINDOW = Duration.ofDays(400);

    private final DeviceRepository devices;
    private final DeviceReadingRepository readings;
    private final ReadingWorkbookWriter workbookWriter;
    private final ReadingPdfWriter pdfWriter;

    /** The formats an export can be requested in. */
    public enum Format {
        XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
        PDF("pdf", "application/pdf");

        private final String extension;
        private final String contentType;

        Format(String extension, String contentType) {
            this.extension = extension;
            this.contentType = contentType;
        }

        public String extension() {
            return extension;
        }

        public String contentType() {
            return contentType;
        }

        public static Format from(String value) {
            for (Format format : values()) {
                if (format.name().equalsIgnoreCase(value == null ? "" : value.trim())) {
                    return format;
                }
            }
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Format must be one of XLSX or PDF.");
        }
    }

    /**
     * What to export.
     *
     * @param deviceId   one device, or null for every device matching the other filters
     * @param deviceType the kind of instrument — water meter, pressure sensor, quality sensor
     * @param transport  the network — LoRaWAN, NB-IoT, cellular. Orthogonal to {@code deviceType}:
     *                   "every pressure sensor" and "everything on LoRaWAN" are different questions
     *                   and a report may ask both at once
     * @param metric     one metric, or null for every metric the matching devices reported
     */
    public record Request(
            UUID deviceId,
            String deviceType,
            String transport,
            String metric,
            Instant from,
            Instant to,
            Format format
    ) {
    }

    /**
     * Writes the export.
     *
     * <p>Read-only and transactional for the whole write, so every page of a long export is read
     * from one consistent snapshot. Without it, readings arriving mid-export — and on a live fleet
     * they arrive constantly — would shift the pages underneath the cursor and the file would
     * repeat some rows and skip others.
     */
    @Transactional(readOnly = true)
    public void export(UUID organizationId, String organizationName, String generatedBy,
                       Request request, OutputStream out) throws IOException {
        validate(request);

        Map<UUID, Device> matched = resolveDevices(organizationId, request);
        ReadingExport.Criteria criteria = criteriaFor(organizationName, generatedBy, request,
                matched);

        int cap = request.format() == Format.XLSX ? MAX_XLSX_ROWS : MAX_PDF_ROWS;

        if (matched.isEmpty()) {
            // An empty export, not an error. "No device matches these filters" is a legitimate
            // answer to a report, and a 404 would make the caller unable to tell it apart from a
            // broken request — while an empty sheet with its criteria on it says exactly what was
            // asked and what came back.
            writeEmpty(out, criteria, request.format());
            return;
        }

        if (request.format() == Format.XLSX) {
            RowCursor cursor = new RowCursor(matched, request, cap);
            workbookWriter.write(out, criteria, cursor, cursor.truncated());
            log.info("Exported {} reading(s) to XLSX for tenant {}", cursor.written(), organizationId);
        } else {
            List<ReadingExport.Row> rows = collect(matched, request, cap);
            boolean truncated = rows.size() >= cap;
            pdfWriter.write(out, criteria, rows, truncated);
            log.info("Exported {} reading(s) to PDF for tenant {}", rows.size(), organizationId);
        }
    }

    /** A suggested filename, so a browser download is self-describing rather than "export". */
    public String filenameFor(Request request) {
        StringBuilder name = new StringBuilder("aquagrid-readings");
        if (request.deviceType() != null) {
            name.append('-').append(request.deviceType().toLowerCase());
        }
        if (request.transport() != null) {
            name.append('-').append(request.transport().toLowerCase());
        }
        name.append('-')
                .append(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")
                        .withZone(java.time.ZoneOffset.UTC).format(request.from()))
                .append('-')
                .append(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")
                        .withZone(java.time.ZoneOffset.UTC).format(request.to()))
                .append('.')
                .append(request.format().extension());
        return name.toString().replaceAll("[^A-Za-z0-9._-]", "-");
    }

    private void validate(Request request) {
        if (request.from() == null || request.to() == null || !request.from().isBefore(request.to())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "The report window must start before it ends.");
        }
        if (Duration.between(request.from(), request.to()).compareTo(MAX_WINDOW) > 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "A single export may cover at most " + MAX_WINDOW.toDays() + " days.");
        }
    }

    /**
     * The devices the filters select.
     *
     * <p>Resolved against the device table rather than folded into the readings query: device type
     * and transport are properties of the device, and joining them per page of a 200,000-row scan
     * would pay for that lookup once per reading. It also supplies the identity every exported row
     * carries, which would otherwise need a second pass.
     */
    private Map<UUID, Device> resolveDevices(UUID organizationId, Request request) {
        List<Device> matched;
        if (request.deviceId() != null) {
            matched = devices.findById(request.deviceId())
                    .filter(device -> device.getOrganizationId().equals(organizationId))
                    // Filtered, not compared: another tenant's device is absent, never forbidden.
                    .map(List::of)
                    .orElse(List.of());
        } else {
            matched = devices.findForTenant(organizationId, null, request.transport(),
                            request.deviceType(), null, null, null, PageRequest.of(0, MAX_DEVICES))
                    .getContent();
        }

        Map<UUID, Device> byId = new HashMap<>(matched.size());
        matched.forEach(device -> byId.put(device.getId(), device));
        return byId;
    }

    private ReadingExport.Criteria criteriaFor(String organizationName, String generatedBy,
                                               Request request, Map<UUID, Device> matched) {
        String deviceCode = request.deviceId() == null
                ? null
                : matched.values().stream().findFirst().map(Device::getDeviceCode).orElse(null);
        return new ReadingExport.Criteria(organizationName, request.from(), request.to(),
                deviceCode, request.deviceType(), request.transport(), request.metric(),
                Instant.now(), generatedBy);
    }

    private List<ReadingExport.Row> collect(Map<UUID, Device> matched, Request request, int cap) {
        List<ReadingExport.Row> rows = new ArrayList<>();
        for (ReadingExport.Row row : new RowCursor(matched, request, cap)) {
            rows.add(row);
        }
        return rows;
    }

    private void writeEmpty(OutputStream out, ReadingExport.Criteria criteria, Format format)
            throws IOException {
        if (format == Format.XLSX) {
            workbookWriter.write(out, criteria, List.of(), false);
        } else {
            pdfWriter.write(out, criteria, List.of(), false);
        }
    }

    /**
     * Pages through the readings, converting each to an export row.
     *
     * <p>An {@link Iterable} rather than a materialised list so the workbook writer can consume it
     * lazily: one page of readings is resident at a time, and the sheet is flushed to disk behind
     * it. A list would put the entire export in memory, which is the thing the streaming workbook
     * exists to avoid.
     */
    private final class RowCursor implements Iterable<ReadingExport.Row> {

        private final Map<UUID, Device> devicesById;
        private final Request request;
        private final int cap;
        private long written;
        private boolean truncated;

        private RowCursor(Map<UUID, Device> devicesById, Request request, int cap) {
            this.devicesById = devicesById;
            this.request = request;
            this.cap = cap;
        }

        long written() {
            return written;
        }

        boolean truncated() {
            return truncated;
        }

        @Override
        public java.util.Iterator<ReadingExport.Row> iterator() {
            return new java.util.Iterator<>() {
                private int pageNumber = 0;
                private List<DeviceReading> page = List.of();
                private int index = 0;
                private boolean exhausted = false;

                @Override
                public boolean hasNext() {
                    if (written >= cap) {
                        truncated = true;
                        return false;
                    }
                    while (index >= page.size() && !exhausted) {
                        fetchNextPage();
                    }
                    return index < page.size();
                }

                @Override
                public ReadingExport.Row next() {
                    if (!hasNext()) {
                        throw new java.util.NoSuchElementException();
                    }
                    written++;
                    return toRow(page.get(index++));
                }

                private void fetchNextPage() {
                    try {
                        Page<DeviceReading> fetched = readings.findForExport(
                                devicesById.keySet(), request.from(), request.to(),
                                request.metric(), PageRequest.of(pageNumber++, PAGE_SIZE));
                        page = fetched.getContent();
                        index = 0;
                        exhausted = !fetched.hasNext();
                    } catch (RuntimeException e) {
                        // The writer is midway through a response whose headers are already sent,
                        // so there is no status code left to change. Wrapped rather than swallowed:
                        // a truncated download is a visible failure, a silently short one is not.
                        throw new UncheckedIOException(new IOException("Export query failed", e));
                    }
                }
            };
        }

        private ReadingExport.Row toRow(DeviceReading reading) {
            Device device = devicesById.get(reading.getDeviceId());
            MetricCatalog.Definition definition = MetricCatalog.of(reading.getMetric());
            return new ReadingExport.Row(
                    reading.getObservedAt(),
                    reading.getReceivedAt(),
                    device == null ? null : device.getDeviceCode(),
                    device == null ? null : device.getName(),
                    device == null ? null : device.getDeviceType(),
                    // The reading's own transport, not the device's current one: a device
                    // re-provisioned onto another network must not have its history relabelled.
                    reading.getTransport(),
                    device == null ? null : device.getSource(),
                    reading.getMetric(),
                    definition.label(),
                    reading.getValue(),
                    reading.getUnit() != null ? reading.getUnit() : definition.unit());
        }
    }
}
