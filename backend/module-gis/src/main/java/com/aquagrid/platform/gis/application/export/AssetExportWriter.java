package com.aquagrid.platform.gis.application.export;

import com.aquagrid.platform.common.error.BusinessException;
import com.aquagrid.platform.common.error.ErrorCode;
import com.aquagrid.platform.gis.api.AttributeDefinition;
import com.aquagrid.platform.gis.application.service.AssetExportService.ExportData;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Turns a catalogue-driven export into a file.
 *
 * <p>All three formats read the same {@link ExportData}, so a field added in Data Management appears
 * in CSV, Excel and GeoJSON at once. None of them names a column.
 */
@Component
@RequiredArgsConstructor
public class AssetExportWriter {

    /** Rows kept in memory before spilling to a temp file. Matches the reading exporter. */
    private static final int WINDOW_ROWS = 200;

    private final ObjectMapper objectMapper;

    /** The formats an asset export can produce. */
    public enum Format {
        CSV("text/csv; charset=UTF-8", "csv"),
        XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx"),
        GEOJSON("application/geo+json", "geojson");

        private final String contentType;
        private final String extension;

        Format(String contentType, String extension) {
            this.contentType = contentType;
            this.extension = extension;
        }

        public String contentType() {
            return contentType;
        }

        public String extension() {
            return extension;
        }

        /** True when the geometry must be read as GeoJSON rather than WKT. */
        public boolean wantsGeoJsonGeometry() {
            return this == GEOJSON;
        }
    }

    public void write(OutputStream out, ExportData data, Format format) throws IOException {
        switch (format) {
            case CSV -> writeCsv(out, data);
            case XLSX -> writeXlsx(out, data);
            case GEOJSON -> writeGeoJson(out, data);
        }
    }

    // ---- CSV ----------------------------------------------------------------------------------

    private void writeCsv(OutputStream out, ExportData data) throws IOException {
        Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        /*
         * A UTF-8 BOM. Excel on Windows opens a BOM-less UTF-8 CSV as the system code page, which
         * turns every Tamil place name in a panchayat column into mojibake — and the operator's
         * conclusion is that the platform corrupted their data, not that their spreadsheet guessed.
         */
        writer.write('﻿');

        writeCsvRow(writer, data.columns().stream().map(AttributeDefinition::displayName).toList());
        for (Map<String, Object> row : data.rows()) {
            writeCsvRow(writer, data.columns().stream()
                    .map(column -> stringOf(row.get(column.fieldName())))
                    .toList());
        }
        writer.flush();
    }

    private static void writeCsvRow(Writer writer, java.util.List<String> cells) throws IOException {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                writer.write(',');
            }
            writer.write(csvCell(cells.get(i)));
        }
        writer.write('\n');
    }

    /**
     * RFC 4180 quoting.
     *
     * <p>Descriptions and WKT geometries both contain commas. An unquoted cell shifts every column
     * after it, which in an asset export means one field's value appearing under another field's
     * heading — data that is wrong rather than obviously broken.
     */
    private static String csvCell(String value) {
        if (value == null) {
            return "";
        }
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0
                && value.indexOf('\r') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    // ---- Excel --------------------------------------------------------------------------------

    private void writeXlsx(OutputStream out, ExportData data) throws IOException {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(WINDOW_ROWS)) {
            // Rows spill to a temp file; without this they are left behind on disk after every
            // export, which on a busy day fills the volume the application runs on.
            workbook.setCompressTempFiles(true);

            Sheet sheet = workbook.createSheet(sheetName(data.layer().getTitle()));
            /*
             * Column widths must be set before any row is written: SXSSF flushes rows out of
             * memory, so autosizing afterwards would size the columns against only the last
             * hundred. A flat width sized to typical content is the honest trade.
             */
            for (int i = 0; i < data.columns().size(); i++) {
                sheet.setColumnWidth(i, 22 * 256);
            }

            CellStyle headerStyle = headerStyle(workbook);
            Row header = sheet.createRow(0);
            for (int i = 0; i < data.columns().size(); i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(data.columns().get(i).displayName());
                cell.setCellStyle(headerStyle);
            }
            sheet.createFreezePane(0, 1);

            int rowIndex = 1;
            for (Map<String, Object> source : data.rows()) {
                Row row = sheet.createRow(rowIndex++);
                for (int i = 0; i < data.columns().size(); i++) {
                    writeCell(row.createCell(i), source.get(data.columns().get(i).fieldName()));
                }
            }
            workbook.write(out);
            // SXSSF's temp files outlive the workbook unless it is told to clean them up.
            workbook.dispose();
        }
    }

    /**
     * Writes a value as the type Excel should treat it as.
     *
     * <p>Numbers written as text are the classic export complaint: the column will not sum, will not
     * sort numerically, and shows a green triangle in every cell. Typing them here is what makes the
     * file usable rather than merely correct.
     */
    private static void writeCell(Cell cell, Object value) {
        switch (value) {
            case null -> cell.setBlank();
            case Number number -> cell.setCellValue(number.doubleValue());
            case Boolean flag -> cell.setCellValue(flag);
            default -> cell.setCellValue(value.toString());
        }
    }

    /**
     * Excel sheet names may not exceed 31 characters or contain {@code : \ / ? * [ ]}.
     *
     * <p>POI throws on a name that breaks either rule, which would turn a layer someone renamed into
     * a failed export rather than a slightly shortened tab.
     */
    private static String sheetName(String title) {
        String cleaned = title.replaceAll("[:\\\\/?*\\[\\]]", " ").trim();
        if (cleaned.isEmpty()) {
            cleaned = "Assets";
        }
        return cleaned.length() > 31 ? cleaned.substring(0, 31) : cleaned;
    }

    private static CellStyle headerStyle(SXSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    // ---- GeoJSON ------------------------------------------------------------------------------

    /**
     * A FeatureCollection, streamed.
     *
     * <p>The geometry attribute becomes the feature's {@code geometry} member rather than a property,
     * which is what makes the file loadable by QGIS and by this platform's own importer. Every other
     * catalogue column becomes a property, keyed by field name rather than display name: a GeoJSON
     * property key is an identifier that round-trips through import, and "Sl. No." is not one.
     */
    private void writeGeoJson(OutputStream out, ExportData data) throws IOException {
        AttributeDefinition geometryColumn = data.columns().stream()
                .filter(c -> "geom".equals(c.resolvedTarget()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "GeoJSON needs the geometry attribute, which is hidden or inactive on "
                                + data.layer().getTitle() + "."));

        try (JsonGenerator json = objectMapper.getFactory().createGenerator(out)) {
            json.writeStartObject();
            json.writeStringField("type", "FeatureCollection");
            json.writeArrayFieldStart("features");

            for (Map<String, Object> row : data.rows()) {
                json.writeStartObject();
                json.writeStringField("type", "Feature");

                Object geometry = row.get(geometryColumn.fieldName());
                json.writeFieldName("geometry");
                if (geometry == null) {
                    json.writeNull();
                } else {
                    // Already a GeoJSON document from ST_AsGeoJSON; re-parsing it to re-serialise it
                    // would cost a full object graph per feature for no change in the bytes.
                    json.writeRawValue(geometry.toString());
                }

                json.writeObjectFieldStart("properties");
                for (AttributeDefinition column : data.columns()) {
                    if (column == geometryColumn) {
                        continue;
                    }
                    json.writeObjectField(column.fieldName(), row.get(column.fieldName()));
                }
                json.writeEndObject();
                json.writeEndObject();
            }
            json.writeEndArray();
            json.writeEndObject();
        }
    }

    private static String stringOf(Object value) {
        return value == null ? "" : value.toString();
    }
}
