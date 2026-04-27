package zas.admin.zia.translation.service.pdf;

import com.vladsch.flexmark.ast.*;
import com.vladsch.flexmark.ext.tables.*;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import zas.admin.zia.translation.service.parser.PageLayout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@Service
public class PdfGenerationService {

    private static final float MARGIN = 50f;
    private static final float BODY_FONT_SIZE = 11f;
    private static final float H1_FONT_SIZE = 15f;
    private static final float H2_FONT_SIZE = 13f;
    private static final float H3_FONT_SIZE = 12f;
    private static final float LINE_HEIGHT_FACTOR = 1.5f;
    private static final float TABLE_CELL_PADDING = 5f;
    private static final float TABLE_BORDER_WIDTH = 0.5f;
    private static final float HEADER_BACKGROUND_GRAY = 0.93f;

    private final String fontPath;
    private final Parser markdownParser;

    public PdfGenerationService(
            @Value("${zia.translation.pdf.font-path:fonts/Arial.ttf}") String fontPath) {
        this.fontPath = fontPath;
        MutableDataSet options = new MutableDataSet();
        options.set(Parser.EXTENSIONS, List.of(TablesExtension.create()));
        this.markdownParser = Parser.builder(options).build();
    }

    public byte[] generatePdf(List<String> markdownPages) throws IOException {
        return generatePdf(markdownPages, List.of());
    }

    public byte[] generatePdf(List<String> markdownPages, List<PageLayout> pageLayouts) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDFont font = loadFont(document);
            for (int i = 0; i < markdownPages.size(); i++) {
                PDRectangle pageSize = resolvePageSize(pageLayouts, i);
                Node doc = markdownParser.parse(markdownPages.get(i));
                renderMarkdownNode(document, font, doc, pageSize);
            }
            if (document.getNumberOfPages() == 0) {
                document.addPage(new PDPage(PDRectangle.A4));
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private PDRectangle resolvePageSize(List<PageLayout> layouts, int index) {
        if (index < layouts.size()) {
            PageLayout layout = layouts.get(index);
            return new PDRectangle(layout.widthPt(), layout.heightPt());
        }
        return PDRectangle.A4;
    }

    private void renderMarkdownNode(PDDocument document, PDFont font, Node root, PDRectangle pageSize) throws IOException {
        PdfPageWriter writer = new PdfPageWriter(document, font, pageSize);
        Node child = root.getFirstChild();
        while (child != null) {
            switch (child) {
                case Heading heading -> {
                    float fontSize = switch (heading.getLevel()) {
                        case 1 -> H1_FONT_SIZE;
                        case 2 -> H2_FONT_SIZE;
                        default -> H3_FONT_SIZE;
                    };
                    writer.ensureSpace(fontSize * LINE_HEIGHT_FACTOR + 10);
                    writer.addVerticalSpace(8);
                    writer.writeWrappedText(heading.getText().toString(), fontSize);
                    writer.addVerticalSpace(4);
                }
                case BulletList bulletList -> renderBulletList(writer, bulletList);
                case OrderedList orderedList -> renderOrderedList(writer, orderedList);
                case TableBlock table -> renderTable(writer, table, font);
                case ThematicBreak ignored -> {
                    writer.ensureSpace(20);
                    writer.drawHorizontalRule();
                }
                default -> {
                    String text = child.getChars().toString().trim();
                    if (!text.isEmpty()) {
                        writer.ensureSpace(BODY_FONT_SIZE * LINE_HEIGHT_FACTOR);
                        writer.writeWrappedText(text, BODY_FONT_SIZE);
                        writer.addVerticalSpace(4);
                    }
                }
            }
            child = child.getNext();
        }
        writer.close();
    }

    private void renderBulletList(PdfPageWriter writer, BulletList list) throws IOException {
        Node item = list.getFirstChild();
        while (item != null) {
            String text = "• " + item.getChars().toString().trim()
                    .replaceAll("^[-*+]\\s*", "")
                    .replace("\n", " ");
            writer.ensureSpace(BODY_FONT_SIZE * LINE_HEIGHT_FACTOR);
            writer.writeWrappedText(text, BODY_FONT_SIZE, MARGIN + 15);
            item = item.getNext();
        }
        writer.addVerticalSpace(4);
    }

    private void renderOrderedList(PdfPageWriter writer, OrderedList list) throws IOException {
        Node item = list.getFirstChild();
        int index = 1;
        while (item != null) {
            String text = index + ". " + item.getChars().toString().trim()
                    .replaceAll("^\\d+[.)\\s]+", "")
                    .replace("\n", " ");
            writer.ensureSpace(BODY_FONT_SIZE * LINE_HEIGHT_FACTOR);
            writer.writeWrappedText(text, BODY_FONT_SIZE, MARGIN + 15);
            item = item.getNext();
            index++;
        }
        writer.addVerticalSpace(4);
    }

    private record TableLayout(int colCount, float colWidth, float rowHeight, float usableWidth, PDFont font) {}

    private void renderTable(PdfPageWriter writer, TableBlock table, PDFont font) throws IOException {
        List<List<String>> rows = collectTableRows(table);
        if (rows.isEmpty()) return;

        int headerRowCount = countHeaderRows(table);
        int colCount = rows.stream().mapToInt(List::size).max().orElse(0);
        float usableWidth = writer.getUsableWidth();
        float colWidth = usableWidth / colCount;
        float rowHeight = BODY_FONT_SIZE * LINE_HEIGHT_FACTOR + 2 * TABLE_CELL_PADDING;
        TableLayout layout = new TableLayout(colCount, colWidth, rowHeight, usableWidth, font);

        writer.ensureSpace(rowHeight * Math.min(rows.size(), 3));

        for (int r = 0; r < rows.size(); r++) {
            writer.ensureSpace(rowHeight);
            renderTableRow(writer, rows.get(r), r < headerRowCount, layout);
        }
        writer.addVerticalSpace(8);
    }

    private List<List<String>> collectTableRows(TableBlock table) {
        List<List<String>> rows = new ArrayList<>();
        Node section = table.getFirstChild();
        while (section != null) {
            Node row = section.getFirstChild();
            while (row != null) {
                if (row instanceof TableRow) {
                    rows.add(collectCells(row));
                }
                row = row.getNext();
            }
            section = section.getNext();
        }
        return rows;
    }

    private List<String> collectCells(Node row) {
        List<String> cells = new ArrayList<>();
        Node cell = row.getFirstChild();
        while (cell != null) {
            if (cell instanceof TableCell) {
                cells.add(cell.getChars().toString()
                        .replaceAll("^\\|\\s*", "").replaceAll("\\s*\\|$", "").trim());
            }
            cell = cell.getNext();
        }
        return cells;
    }

    private int countHeaderRows(TableBlock table) {
        int count = 0;
        Node section = table.getFirstChild();
        while (section != null) {
            if (section instanceof TableHead) {
                Node row = section.getFirstChild();
                while (row != null) {
                    if (row instanceof TableRow) count++;
                    row = row.getNext();
                }
            }
            section = section.getNext();
        }
        return count;
    }

    private void renderTableRow(PdfPageWriter writer, List<String> row,
                                boolean isHeader, TableLayout layout) throws IOException {
        float y = writer.getCurrentY();
        if (isHeader) {
            writer.drawHeaderBackground(y - layout.rowHeight(), layout.usableWidth(), layout.rowHeight());
        }
        float x = MARGIN;
        for (int c = 0; c < layout.colCount(); c++) {
            writer.drawCellBorder(x, y, layout.colWidth(), layout.rowHeight());
            String cellText = c < row.size() ? filterUnencodable(row.get(c), layout.font()) : "";
            float textWidth = layout.colWidth() - 2 * TABLE_CELL_PADDING;
            String displayText = truncateToFit(cellText, layout.font(), textWidth);
            writer.drawCellText(displayText, x + TABLE_CELL_PADDING,
                    y - TABLE_CELL_PADDING - BODY_FONT_SIZE);
            x += layout.colWidth();
        }
        writer.advanceY(layout.rowHeight());
    }

    private String truncateToFit(String text, PDFont font, float maxWidth) throws IOException {
        if (text.isEmpty()) return text;
        float width = font.getStringWidth(text) / 1000f * BODY_FONT_SIZE;
        if (width <= maxWidth) return text;
        for (int i = text.length() - 1; i > 0; i--) {
            String candidate = text.substring(0, i) + "…";
            float w = font.getStringWidth(filterUnencodable(candidate, font)) / 1000f * BODY_FONT_SIZE;
            if (w <= maxWidth) return candidate;
        }
        return "";
    }

    private String filterUnencodable(String text, PDFont font) {
        StringBuilder sb = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            try {
                font.encode(Character.toString(cp));
                sb.appendCodePoint(cp);
            } catch (IOException | IllegalArgumentException e) {
                // skip
            }
            i += Character.charCount(cp);
        }
        return sb.toString();
    }

    private PDFont loadFont(PDDocument document) throws IOException {
        try (InputStream fontStream = new ClassPathResource(fontPath).getInputStream()) {
            return PDType0Font.load(document, fontStream);
        }
    }

    /**
     * Helper that manages page creation, cursor position, and content stream lifecycle.
     */
    private class PdfPageWriter {
        private final PDDocument document;
        private final PDFont font;
        private final PDRectangle pageSize;
        private PDPageContentStream cs;
        private float currentY;
        private final float pageWidth;
        private final float pageHeight;

        PdfPageWriter(PDDocument document, PDFont font, PDRectangle pageSize) throws IOException {
            this.document = document;
            this.font = font;
            this.pageSize = pageSize;
            this.pageWidth = pageSize.getWidth();
            this.pageHeight = pageSize.getHeight();
            newPage();
        }

        private void newPage() throws IOException {
            if (cs != null) cs.close();
            PDPage page = new PDPage(pageSize);
            document.addPage(page);
            cs = new PDPageContentStream(document, page);
            currentY = pageHeight - MARGIN;
        }

        float getUsableWidth() { return pageWidth - 2 * MARGIN; }

        void ensureSpace(float needed) throws IOException {
            if (currentY - needed < MARGIN) {
                newPage();
            }
        }

        float getCurrentY() { return currentY; }

        void advanceY(float amount) { currentY -= amount; }

        void addVerticalSpace(float space) throws IOException {
            currentY -= space;
            if (currentY < MARGIN) newPage();
        }

        void writeWrappedText(String text, float fontSize) throws IOException {
            writeWrappedText(text, fontSize, MARGIN);
        }

        void writeWrappedText(String text, float fontSize, float leftMargin) throws IOException {
            float lineHeight = fontSize * LINE_HEIGHT_FACTOR;
            float maxWidth = pageWidth - leftMargin - MARGIN;
            List<String> lines = splitIntoLines(filterUnencodable(text, font), fontSize, maxWidth);
            for (String line : lines) {
                ensureSpace(lineHeight);
                cs.beginText();
                cs.setFont(font, fontSize);
                cs.newLineAtOffset(leftMargin, currentY);
                cs.showText(line);
                cs.endText();
                currentY -= lineHeight;
            }
        }

        void drawHorizontalRule() throws IOException {
            cs.setLineWidth(0.5f);
            cs.moveTo(MARGIN, currentY);
            cs.lineTo(pageWidth - MARGIN, currentY);
            cs.stroke();
            currentY -= 10;
        }

        void drawHeaderBackground(float y, float w, float h) throws IOException {
            cs.setNonStrokingColor(HEADER_BACKGROUND_GRAY, HEADER_BACKGROUND_GRAY, HEADER_BACKGROUND_GRAY);
            cs.addRect(MARGIN, y, w, h);
            cs.fill();
            cs.setNonStrokingColor(0f, 0f, 0f);
        }

        void drawCellBorder(float x, float y, float w, float h) throws IOException {
            cs.setStrokingColor(0.6f, 0.6f, 0.6f);
            cs.setLineWidth(TABLE_BORDER_WIDTH);
            cs.addRect(x, y - h, w, h);
            cs.stroke();
            cs.setStrokingColor(0f, 0f, 0f);
        }

        void drawCellText(String text, float x, float y) throws IOException {
            cs.beginText();
            cs.setFont(font, BODY_FONT_SIZE);
            cs.newLineAtOffset(x, y);
            cs.showText(text);
            cs.endText();
        }

        private List<String> splitIntoLines(String text, float fontSize, float maxWidth) throws IOException {
            List<String> result = new ArrayList<>();
            for (String paragraph : text.split("\n", -1)) {
                String[] words = paragraph.split(" ", -1);
                StringBuilder current = new StringBuilder();
                for (String word : words) {
                    String candidate = current.isEmpty() ? word : current + " " + word;
                    float width = font.getStringWidth(candidate) / 1000f * fontSize;
                    if (width > maxWidth && !current.isEmpty()) {
                        result.add(current.toString());
                        current = new StringBuilder(word);
                    } else {
                        current = new StringBuilder(candidate);
                    }
                }
                result.add(current.toString());
            }
            return result;
        }

        void close() throws IOException {
            if (cs != null) cs.close();
        }
    }
}
