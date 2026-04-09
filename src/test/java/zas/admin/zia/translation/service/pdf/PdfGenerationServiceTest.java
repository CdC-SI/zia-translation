package zas.admin.zia.translation.service.pdf;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PdfGenerationServiceTest {

    private final PdfGenerationService service = new PdfGenerationService();

    @Test
    void generatePdf_singlePage_producesValidPdf() throws IOException {
        byte[] pdfBytes = service.generatePdf(List.of("Hello world"));
        assertThat(pdfBytes).isNotEmpty();
        // Verify it's a valid PDF
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(1);
        }
    }

    @Test
    void generatePdf_multiplePages_producesCorrectPageCount() throws IOException {
        List<String> pages = List.of("Page 1 text", "Page 2 text", "Page 3 text");
        byte[] pdfBytes = service.generatePdf(pages);
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(3);
        }
    }

    @Test
    void generatePdf_emptyTextList_producesEmptyDocument() throws IOException {
        byte[] pdfBytes = service.generatePdf(List.of());
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            assertThat(doc.getNumberOfPages()).isZero();
        }
    }

    @Test
    void generatePdf_longText_wrapsWithoutException() throws IOException {
        String longText = "word ".repeat(200);
        byte[] pdfBytes = service.generatePdf(List.of(longText));
        assertThat(pdfBytes).isNotEmpty();
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(1);
        }
    }

    @Test
    void generatePdf_textWithNewlines_producesValidPdf() throws IOException {
        String multilineText = "Line 1\nLine 2\nLine 3";
        byte[] pdfBytes = service.generatePdf(List.of(multilineText));
        assertThat(pdfBytes).isNotEmpty();
    }
}
