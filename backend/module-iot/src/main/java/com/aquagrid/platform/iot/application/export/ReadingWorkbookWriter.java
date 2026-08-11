package com.aquagrid.platform.iot.application.export;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Writes readings to an .xlsx workbook.
 *
 * <p><b>Streaming, via {@code SXSSFWorkbook}.</b> The ordinary {@code XSSFWorkbook} builds the whole
 * spreadsheet as a DOM before writing a byte, so a year of a fleet's readings would be held in
 * memory twice — as rows and as the XML they serialise to — and a single wide date range would be
 * enough to exhaust the heap. SXSSF keeps a sliding window of rows and flushes the rest to a temp
 * file, which is what makes the export bounded by disk rather than by RAM.
 *
 * <p>The consequence is that column widths must be set <em>before</em> any row is written: once a
 * row has been flushed it is no longer addressable, so autosizing after the fact would silently size
 * the columns against only the last hundred rows.
 */
@Component
public class ReadingWorkbookWriter {

    /** Rows kept in memory; the rest are flushed to disk. */
    private static final int WINDOW_ROWS = 200;

    /**
     * Column widths in Excel's units (1/256th of a character), set up front because SXSSF cannot
     * autosize a flushed row. Sized to the content each column actually holds.
     */
    private static final int[] COLUMN_WIDTHS =
            {21 * 256, 21 * 256, 18 * 256, 28 * 256, 16 * 256, 12 * 256, 11 * 256, 18 * 256,
                    14 * 256, 8 * 256};

    private static final DateTimeFormatter TIMESTAMPS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    /**
     * Writes the workbook and closes nothing the caller owns.
     *
     * @param rows      the readings, already ordered and bounded by the caller
     * @param truncated whether the query hit the export cap, stated on the sheet rather than only
     *                  in a response header — the file is what gets forwarded, and a spreadsheet
     *                  that silently stops at a round number is one somebody will reconcile against
     */
    public void write(OutputStream out, ReadingExport.Criteria criteria,
                      Iterable<ReadingExport.Row> rows, boolean truncated) throws IOException {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(WINDOW_ROWS)) {
            // Rows spill to a temp file; without this they are left behind on disk after every
            // export, which on a busy reporting day fills the volume the application runs on.
            workbook.setCompressTempFiles(true);

            Sheet sheet = workbook.createSheet("Readings");
            for (int column = 0; column < COLUMN_WIDTHS.length; column++) {
                sheet.setColumnWidth(column, COLUMN_WIDTHS[column]);
            }

            CellStyle titleStyle = titleStyle(workbook);
            CellStyle headerStyle = headerStyle(workbook);

            int rowIndex = 0;
            rowIndex = writeTitle(sheet, titleStyle, criteria, rowIndex);
            rowIndex = writeHeader(sheet, headerStyle, rowIndex);

            // The header stays visible while scrolling a hundred thousand rows. Frozen after the
            // header is written, because the pane is expressed in row indices.
            sheet.createFreezePane(0, rowIndex);

            long written = 0;
            for (ReadingExport.Row reading : rows) {
                writeRow(sheet, rowIndex++, reading);
                written++;
            }

            writeFooter(sheet, rowIndex + 1, written, truncated);
            workbook.write(out);
            // Deletes the temp files the sliding window created. Not done by close().
            workbook.dispose();
        }
    }

    private static int writeTitle(Sheet sheet, CellStyle style, ReadingExport.Criteria criteria,
                                  int rowIndex) {
        Row title = sheet.createRow(rowIndex++);
        cell(title, 0, "AquaGrid — device readings", style);

        Row scope = sheet.createRow(rowIndex++);
        cell(scope, 0, criteria.describe(), null);

        Row window = sheet.createRow(rowIndex++);
        cell(window, 0, "Window: " + TIMESTAMPS.format(criteria.from())
                + " to " + TIMESTAMPS.format(criteria.to()) + " UTC", null);

        Row provenance = sheet.createRow(rowIndex++);
        // Who ran it and when. A report with no provenance cannot be re-run or challenged, and
        // these files are forwarded far from the screen that produced them.
        cell(provenance, 0, "Generated " + TIMESTAMPS.format(criteria.generatedAt()) + " UTC"
                + (criteria.generatedBy() == null ? "" : " by " + criteria.generatedBy())
                + (criteria.organizationName() == null ? "" : " · " + criteria.organizationName()),
                null);

        sheet.createRow(rowIndex++);
        return rowIndex;
    }

    private static int writeHeader(Sheet sheet, CellStyle style, int rowIndex) {
        Row header = sheet.createRow(rowIndex++);
        List<String> columns = ReadingExport.COLUMNS;
        for (int i = 0; i < columns.size(); i++) {
            cell(header, i, columns.get(i), style);
        }
        return rowIndex;
    }

    /**
     * Writes one reading.
     *
     * <p>The value goes in as a number, not text. A spreadsheet whose measurements are strings
     * cannot be summed, charted or conditionally formatted — which is most of why the recipient
     * asked for a spreadsheet rather than a PDF.
     */
    private static void writeRow(Sheet sheet, int rowIndex, ReadingExport.Row reading) {
        Row row = sheet.createRow(rowIndex);
        List<String> cells = ReadingExport.cells(reading, TIMESTAMPS);
        for (int i = 0; i < cells.size(); i++) {
            if (i == 8 && reading.value() != null) {
                row.createCell(i).setCellValue(reading.value());
            } else {
                cell(row, i, cells.get(i), null);
            }
        }
    }

    private static void writeFooter(Sheet sheet, int rowIndex, long written, boolean truncated) {
        Row total = sheet.createRow(rowIndex);
        cell(total, 0, written + " reading" + (written == 1 ? "" : "s"), null);
        if (truncated) {
            Row note = sheet.createRow(rowIndex + 1);
            cell(note, 0, "This export reached the maximum row count and is incomplete — "
                    + "narrow the window or the filters to export the whole period.", null);
        }
    }

    private static void cell(Row row, int column, String value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value);
        if (style != null) {
            cell.setCellStyle(style);
        }
    }

    private static CellStyle titleStyle(SXSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 14);
        style.setFont(font);
        return style;
    }

    private static CellStyle headerStyle(SXSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN);
        style.setAlignment(HorizontalAlignment.LEFT);
        return style;
    }
}
