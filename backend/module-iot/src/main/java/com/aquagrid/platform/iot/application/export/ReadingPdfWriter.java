package com.aquagrid.platform.iot.application.export;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Writes readings to a paginated PDF.
 *
 * <p>Laid out by hand, because the table libraries that would do it are AGPL or LGPL and this
 * platform ships to customers whose procurement reads licences. PDFBox is Apache-2.0; the cost is
 * the pagination arithmetic below, paid once here rather than by every customer's legal review.
 *
 * <p>A PDF is a different artefact from the spreadsheet, not a second copy of it. It is what gets
 * attached to a regulatory submission or signed off, so it is fixed-width, paginated, and carries
 * its provenance and page numbers on every page — a sheet detached from its header must still say
 * what it is and whether anything followed it.
 */
@Component
public class ReadingPdfWriter {

    private static final DateTimeFormatter TIMESTAMPS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private static final float MARGIN = 28f;
    private static final float ROW_HEIGHT = 13f;
    private static final float BODY_SIZE = 7.5f;
    private static final float HEADER_SIZE = 8f;

    /**
     * Column widths in points, summing to the printable width of a landscape A4 page.
     *
     * <p>Fixed rather than content-derived: a report whose columns move between pages, or between
     * two runs of the same query, is one nobody can read down a column of.
     */
    private static final float[] COLUMN_WIDTHS =
            {95f, 95f, 85f, 130f, 85f, 60f, 55f, 90f, 65f, 35f};

    /**
     * Writes the document.
     *
     * @param rows      readings, already ordered and bounded. Materialised rather than streamed
     *                  because a PDF beyond a few thousand rows is not a document anyone reads —
     *                  the caller's cap is much lower than the spreadsheet's for that reason
     * @param truncated whether the cap was reached, stated on the last page
     */
    public void write(OutputStream out, ReadingExport.Criteria criteria,
                      List<ReadingExport.Row> rows, boolean truncated) throws IOException {
        try (PDDocument document = new PDDocument()) {
            var regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            var bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

            PDRectangle size = new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth());
            int pageNumber = 0;
            int index = 0;

            do {
                pageNumber++;
                PDPage page = new PDPage(size);
                document.addPage(page);

                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    float y = size.getHeight() - MARGIN;

                    // The title block is only on page one; every page after it starts at the column
                    // header, which is what a reader needs on a sheet pulled out of the middle.
                    if (pageNumber == 1) {
                        y = writeTitle(content, bold, regular, criteria, y);
                    }
                    y = writeColumnHeader(content, bold, y);

                    float bottom = MARGIN + ROW_HEIGHT * 2;
                    while (index < rows.size() && y > bottom) {
                        writeRow(content, regular, rows.get(index++), y);
                        y -= ROW_HEIGHT;
                    }

                    writePageFooter(content, regular, size, criteria, pageNumber,
                            index >= rows.size(), rows.size(), truncated);
                }
            } while (index < rows.size());

            document.save(out);
        }
    }

    private static float writeTitle(PDPageContentStream content, PDType1Font bold,
                                    PDType1Font regular, ReadingExport.Criteria criteria, float y)
            throws IOException {
        text(content, bold, 13f, MARGIN, y, "AquaGrid — device readings");
        y -= 16f;
        text(content, regular, 9f, MARGIN, y, criteria.describe());
        y -= 12f;
        text(content, regular, 9f, MARGIN, y,
                "Window: " + TIMESTAMPS.format(criteria.from())
                        + " to " + TIMESTAMPS.format(criteria.to()) + " UTC");
        y -= 12f;
        text(content, regular, 9f, MARGIN, y,
                "Generated " + TIMESTAMPS.format(criteria.generatedAt()) + " UTC"
                        + (criteria.generatedBy() == null ? "" : " by " + criteria.generatedBy())
                        + (criteria.organizationName() == null
                        ? "" : " · " + criteria.organizationName()));
        return y - 18f;
    }

    private static float writeColumnHeader(PDPageContentStream content, PDType1Font bold, float y)
            throws IOException {
        float x = MARGIN;
        List<String> columns = ReadingExport.COLUMNS;
        for (int i = 0; i < columns.size(); i++) {
            text(content, bold, HEADER_SIZE, x, y, columns.get(i));
            x += COLUMN_WIDTHS[i];
        }
        y -= 4f;
        content.moveTo(MARGIN, y);
        content.lineTo(MARGIN + totalWidth(), y);
        content.setLineWidth(0.5f);
        content.stroke();
        return y - ROW_HEIGHT;
    }

    private static void writeRow(PDPageContentStream content, PDType1Font regular,
                                 ReadingExport.Row row, float y) throws IOException {
        float x = MARGIN;
        List<String> cells = ReadingExport.cells(row, TIMESTAMPS);
        for (int i = 0; i < cells.size(); i++) {
            text(content, regular, BODY_SIZE, x, y, clip(cells.get(i), COLUMN_WIDTHS[i], regular));
            x += COLUMN_WIDTHS[i];
        }
    }

    private static void writePageFooter(PDPageContentStream content, PDType1Font regular,
                                        PDRectangle size, ReadingExport.Criteria criteria,
                                        int pageNumber, boolean lastPage, int total,
                                        boolean truncated) throws IOException {
        String left = total + " reading" + (total == 1 ? "" : "s")
                + (lastPage && truncated
                ? " — export reached its maximum size and is incomplete; narrow the window."
                : "");
        text(content, regular, 7.5f, MARGIN, MARGIN, left);

        // Page number without a total: the document is written in one pass, so the count is not
        // known until the last page. "Page 3" is honest; "Page 3 of 3" written on every page
        // would not be.
        String right = "Page " + pageNumber;
        float width = textWidth(regular, 7.5f, right);
        text(content, regular, 7.5f, size.getWidth() - MARGIN - width, MARGIN, right);
    }

    private static void text(PDPageContentStream content, PDType1Font font, float sizePt,
                             float x, float y, String value) throws IOException {
        content.beginText();
        content.setFont(font, sizePt);
        content.newLineAtOffset(x, y);
        content.showText(sanitise(value));
        content.endText();
    }

    /**
     * Truncates a cell to its column.
     *
     * <p>Clipped with an ellipsis rather than allowed to overrun: a long device name spilling into
     * the next column does not look like an overflow, it looks like the neighbouring value.
     */
    private static String clip(String value, float width, PDType1Font font) throws IOException {
        float available = width - 6f;
        if (textWidth(font, ReadingPdfWriter.BODY_SIZE, value) <= available) {
            return value;
        }
        String candidate = value;
        while (!candidate.isEmpty()
                && textWidth(font, ReadingPdfWriter.BODY_SIZE, candidate + "...") > available) {
            candidate = candidate.substring(0, candidate.length() - 1);
        }
        return candidate + "...";
    }

    private static float textWidth(PDType1Font font, float sizePt, String value) throws IOException {
        return font.getStringWidth(sanitise(value)) / 1000f * sizePt;
    }

    /**
     * Replaces characters the standard-14 fonts cannot encode.
     *
     * <p>WinAnsi has no glyph for much beyond Latin-1, and {@code showText} throws rather than
     * substituting — so a device named in Malayalam or Tamil, which this platform's customers
     * certainly have, would fail the whole export rather than one cell.
     */
    private static String sanitise(String value) {
        StringBuilder safe = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            safe.append(c >= 32 && c <= 255 ? c : '?');
        }
        return safe.toString();
    }

    private static float totalWidth() {
        float total = 0f;
        for (float width : COLUMN_WIDTHS) {
            total += width;
        }
        return total;
    }
}
