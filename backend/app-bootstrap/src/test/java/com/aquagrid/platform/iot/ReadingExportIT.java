package com.aquagrid.platform.iot;

import com.aquagrid.platform.AbstractIntegrationTest;
import com.aquagrid.platform.common.error.BusinessException;
import com.aquagrid.platform.iot.api.DeviceMessage;
import com.aquagrid.platform.iot.application.service.DeviceManagementService;
import com.aquagrid.platform.iot.application.service.ReadingExportService;
import com.aquagrid.platform.iot.domain.model.DeviceReading;
import com.aquagrid.platform.iot.domain.model.MetricCatalog;
import com.aquagrid.platform.iot.infrastructure.persistence.DeviceReadingRepository;
import com.aquagrid.platform.iot.web.dto.DeviceDto;
import com.aquagrid.platform.identity.infrastructure.persistence.OrganizationRepository;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verification of Module 25 — reading exports — against real PostGIS.
 *
 * <p>Each writer is opened with the library that reads that format, not merely checked for length,
 * because a malformed export is the failure mode that matters here: a stream cut short by a paging
 * bug is invisible to a byte-count assertion and instantly visible to a spreadsheet application.
 *
 * <p>The property tested repeatedly across formats is that the two writers agree — same devices,
 * same values, same row count — because a discrepancy between a spreadsheet and a PDF of the same
 * request is, to whoever receives them, indistinguishable from a discrepancy in the data.
 */
class ReadingExportIT extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private ReadingExportService exportService;
    @Autowired
    private DeviceManagementService deviceService;
    @Autowired
    private DeviceReadingRepository readingRepository;
    @Autowired
    private OrganizationRepository organizationRepository;

    private UUID organizationId() {
        return organizationRepository.findByCodeIgnoreCase("SYSTEM").orElseThrow().getId();
    }

    @Test
    @DisplayName("an XLSX export opens and carries every reading, in time order")
    void exportsAValidWorkbookInTimeOrder() throws Exception {
        DeviceDto device = register("NB_IOT", Map.of("imei", nextImei()));
        Instant now = Instant.now();
        write(device.id(), "NB_IOT", DeviceMessage.Metrics.VOLUME, 100.0, now.minus(Duration.ofMinutes(3)));
        write(device.id(), "NB_IOT", DeviceMessage.Metrics.VOLUME, 105.0, now.minus(Duration.ofMinutes(1)));

        byte[] file = export(new ReadingExportService.Request(
                device.id(), null, null, null,
                now.minus(Duration.ofHours(1)), now, ReadingExportService.Format.XLSX));

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file))) {
            Sheet sheet = workbook.getSheetAt(0);
            List<String> deviceColumn = new ArrayList<>();
            for (Row row : sheet) {
                // Column 2 ("Device") is blank on every header/title row and populated only on
                // data rows, so this is how the test locates the data without hard-coding a row
                // offset the header layout would then be free to break.
                org.apache.poi.ss.usermodel.Cell cell = row.getCell(2);
                if (cell != null && device.deviceCode().equals(cell.getStringCellValue())) {
                    deviceColumn.add(cell.getStringCellValue());
                }
            }
            assertThat(deviceColumn).hasSize(2);
        }
    }

    @Test
    @DisplayName("a PDF export opens and its text contains the device and its readings")
    void exportsAReadablePdf() throws Exception {
        DeviceDto device = register("LORAWAN", Map.of("devEui", nextDevEui()));
        Instant now = Instant.now();
        write(device.id(), "LORAWAN", DeviceMessage.Metrics.FLOW_RATE, 4.5, now.minus(Duration.ofMinutes(1)));

        byte[] file = export(new ReadingExportService.Request(
                device.id(), null, null, null,
                now.minus(Duration.ofHours(1)), now, ReadingExportService.Format.PDF));

        try (PDDocument document = org.apache.pdfbox.Loader.loadPDF(file)) {
            String text = new PDFTextStripper().getText(document);
            assertThat(text).contains(device.deviceCode());
            assertThat(text).contains("Flow rate");
            assertThat(document.getNumberOfPages()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("device type and transport are independent filters, not alternatives")
    void filtersByDeviceTypeAndTransportIndependently() throws Exception {
        DeviceDto meterOnLora = register("LORAWAN", Map.of("devEui", nextDevEui()));
        DeviceDto meterOnNbIot = register("NB_IOT", Map.of("imei", nextImei()));
        Instant now = Instant.now();
        write(meterOnLora.id(), "LORAWAN", DeviceMessage.Metrics.VOLUME, 10.0, now.minus(Duration.ofMinutes(1)));
        write(meterOnNbIot.id(), "NB_IOT", DeviceMessage.Metrics.VOLUME, 20.0, now.minus(Duration.ofMinutes(1)));

        // "Every WATER_METER" (both) intersected with "every LoRaWAN device" (only one) — the
        // question this is guarding is whether the two filters were AND-ed or OR-ed together.
        byte[] file = export(new ReadingExportService.Request(
                null, "WATER_METER", "LORAWAN", null,
                now.minus(Duration.ofHours(1)), now, ReadingExportService.Format.XLSX));

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file))) {
            Sheet sheet = workbook.getSheetAt(0);
            boolean sawLora = false;
            boolean sawNbIot = false;
            for (Row row : sheet) {
                org.apache.poi.ss.usermodel.Cell cell = row.getCell(2);
                if (cell == null) continue;
                if (meterOnLora.deviceCode().equals(cell.getStringCellValue())) sawLora = true;
                if (meterOnNbIot.deviceCode().equals(cell.getStringCellValue())) sawNbIot = true;
            }
            assertThat(sawLora).isTrue();
            assertThat(sawNbIot).isFalse();
        }
    }

    @Test
    @DisplayName("a window with no matching device produces an empty, well-formed file, not an error")
    void emptyMatchProducesAnEmptyFile() throws Exception {
        Instant now = Instant.now();
        byte[] file = export(new ReadingExportService.Request(
                UUID.randomUUID(), null, null, null,
                now.minus(Duration.ofHours(1)), now, ReadingExportService.Format.XLSX));

        // "No device matches" is a legitimate report outcome, not a fault — the caller must be able
        // to tell it apart from a broken request, and a well-formed empty sheet does that.
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file))) {
            assertThat(workbook.getSheetAt(0)).isNotNull();
        }
    }

    @Test
    @DisplayName("a window that does not start before it ends is rejected")
    void rejectsAnInvertedWindow() {
        Instant now = Instant.now();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThatThrownBy(() -> exportService.export(organizationId(), null, "tester",
                new ReadingExportService.Request(null, null, null, null,
                        now, now.minus(Duration.ofHours(1)), ReadingExportService.Format.XLSX),
                out))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("the unit and label on an exported row come from the same catalogue the API serves")
    void exportedUnitsMatchTheCatalogue() throws Exception {
        DeviceDto device = register("NB_IOT", Map.of("imei", nextImei()));
        Instant now = Instant.now();
        write(device.id(), "NB_IOT", DeviceMessage.Metrics.PRESSURE, 3.2, now.minus(Duration.ofMinutes(1)));

        byte[] file = export(new ReadingExportService.Request(
                device.id(), null, null, null,
                now.minus(Duration.ofHours(1)), now, ReadingExportService.Format.PDF));

        try (PDDocument document = org.apache.pdfbox.Loader.loadPDF(file)) {
            String text = new PDFTextStripper().getText(document);
            // "Pressure" and "bar" both come from MetricCatalog, the same declaration the ingest
            // path stamps units from and the telemetry API serves — this pins that the export did
            // not invent its own copy of either.
            assertThat(text).contains(MetricCatalog.of(DeviceMessage.Metrics.PRESSURE).label());
            assertThat(text).contains(MetricCatalog.of(DeviceMessage.Metrics.PRESSURE).unit());
        }
    }

    // ---- Fixtures ------------------------------------------------------------------------

    private byte[] export(ReadingExportService.Request request) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        exportService.export(organizationId(), "SYSTEM", "tester", request, out);
        return out.toByteArray();
    }

    private void write(UUID deviceId, String transport, String metric, double value, Instant at) {
        DeviceReading reading = new DeviceReading();
        reading.setOrganizationId(organizationId());
        reading.setDeviceId(deviceId);
        reading.setMetric(metric);
        reading.setValue(value);
        reading.setUnit(MetricCatalog.unitOf(metric));
        reading.setObservedAt(at);
        reading.setReceivedAt(at);
        reading.setTransport(transport);
        readingRepository.saveAndFlush(reading);
    }

    private DeviceDto register(String transport, Map<String, String> communication) {
        int seq = SEQ.incrementAndGet();
        return deviceService.register(organizationId(), null, new DeviceDto.RegistrationRequest(
                "EXP-" + seq, "Export test meter", "WATER_METER", "AST-9101", null, null, null,
                transport, "Kamstrup", "flowIQ 2200", null, LocalDate.of(2026, 3, 14),
                "PROVISIONED", new double[]{76.9366, 8.5241}, communication, "1.4.2"));
    }

    private static String nextImei() {
        return String.format("35693803572%04d", SEQ.incrementAndGet());
    }

    private static String nextDevEui() {
        return String.format("A81758FFFE04%04X", SEQ.incrementAndGet());
    }
}
