package zas.admin.zia.translation.service.parser;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PdfDocumentParserTest {

    private final PdfDocumentParser parser = new PdfDocumentParser();

    @Test
    void supportedMimeType_returnsPdf() {
        assertThat(parser.supportedMimeType()).isEqualTo("application/pdf");
    }

    @Test
    void renderPages_singlePagePdf_returnsOnePageImage() throws IOException {
        byte[] pdfBytes = createMinimalPdf(1);
        List<byte[]> pages = parser.renderPages(pdfBytes);
        assertThat(pages).hasSize(1);
        assertThat(pages.getFirst()).isNotEmpty();
    }

    @Test
    void renderPages_multiPagePdf_returnsCorrectNumberOfPages() throws IOException {
        byte[] pdfBytes = createMinimalPdf(3);
        List<byte[]> pages = parser.renderPages(pdfBytes);
        assertThat(pages).hasSize(3);
    }

    @Test
    void renderPages_producesValidPngBytes() throws IOException {
        byte[] pdfBytes = createMinimalPdf(1);
        List<byte[]> pages = parser.renderPages(pdfBytes);
        // PNG magic bytes: 89 50 4E 47
        byte[] pageBytes = pages.getFirst();
        assertThat(pageBytes[0]).isEqualTo((byte) 0x89);
        assertThat(pageBytes[1]).isEqualTo((byte) 0x50); // 'P'
        assertThat(pageBytes[2]).isEqualTo((byte) 0x4E); // 'N'
        assertThat(pageBytes[3]).isEqualTo((byte) 0x47); // 'G'
    }

    private static byte[] createMinimalPdf(int pageCount) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pageCount; i++) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.beginText();
                    cs.newLineAtOffset(50, 700);
                    cs.showText("Page " + (i + 1));
                    cs.endText();
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }
}
