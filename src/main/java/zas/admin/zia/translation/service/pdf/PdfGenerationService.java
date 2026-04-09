package zas.admin.zia.translation.service.pdf;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

@Service
public class PdfGenerationService {

    private static final float MARGIN = 50f;
    private static final float FONT_SIZE = 11f;
    private static final float LINE_HEIGHT = FONT_SIZE * 1.4f;

    public byte[] generatePdf(List<String> pages) throws IOException {
        try (PDDocument document = new PDDocument()) {
            for (String pageText : pages) {
                addPage(document, pageText);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private void addPage(PDDocument document, String text) throws IOException {
        PDPage page = new PDPage(PDRectangle.A4);
        document.addPage(page);

        PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        float usableWidth = page.getMediaBox().getWidth() - 2 * MARGIN;
        float startY = page.getMediaBox().getHeight() - MARGIN;

        try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
            cs.setFont(font, FONT_SIZE);
            cs.beginText();
            cs.newLineAtOffset(MARGIN, startY);

            for (String line : splitIntoLines(text, font, usableWidth)) {
                cs.showText(sanitize(line));
                cs.newLineAtOffset(0, -LINE_HEIGHT);
            }

            cs.endText();
        }
    }

    private List<String> splitIntoLines(String text, PDType1Font font, float maxWidth) throws IOException {
        List<String> result = new java.util.ArrayList<>();
        for (String paragraph : text.split("\n", -1)) {
            String[] words = paragraph.split(" ", -1);
            StringBuilder current = new StringBuilder();
            for (String word : words) {
                String candidate = current.isEmpty() ? word : current + " " + word;
                float width = font.getStringWidth(sanitize(candidate)) / 1000f * FONT_SIZE;
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

    /**
     * Replaces characters that are not encodable in WinAnsiEncoding (PDFBox default for PDType1Font)
     * with a '?' to avoid runtime errors.
     */
    private String sanitize(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            if (c < 0x20 && c != '\t') {
                continue;
            }
            // PDType1Font uses WinAnsiEncoding (Latin-1 range)
            if (c > 0xFF) {
                sb.append('?');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
