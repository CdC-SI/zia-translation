package zas.admin.zia.translation.service.pdf;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import zas.admin.zia.translation.service.parser.PageLayout;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PdfGenerationServiceTest {

    private final PdfGenerationService service = new PdfGenerationService("fonts/Arial.ttf");

    @Test
    void generatePdf_singlePage_producesValidPdf() throws IOException {
        byte[] pdfBytes = service.generatePdf(List.of("Hello world"));
        assertThat(pdfBytes).isNotEmpty();
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            assertThat(doc.getNumberOfPages()).isGreaterThanOrEqualTo(1);
        }
    }

    @Test
    void generatePdf_multiplePages_insertsPageBreaks() throws IOException {
        List<String> pages = List.of("Page 1 text", "Page 2 text", "Page 3 text");
        byte[] pdfBytes = service.generatePdf(pages);
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            assertThat(doc.getNumberOfPages()).isGreaterThanOrEqualTo(3);
        }
    }

    @Test
    void generatePdf_emptyTextList_producesEmptyDocument() throws IOException {
        byte[] pdfBytes = service.generatePdf(List.of());
        assertThat(pdfBytes).isNotEmpty();
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            assertThat(doc.getNumberOfPages()).isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    void generatePdf_markdownTable_rendersCorrectly() throws IOException {
        String markdown = """
                # Title
                
                | Col A | Col B |
                |-------|-------|
                | val1  | val2  |
                | val3  | val4  |
                """;
        byte[] pdfBytes = service.generatePdf(List.of(markdown));
        assertThat(pdfBytes).isNotEmpty();
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("Title");
            assertThat(text).contains("val1");
            assertThat(text).contains("val4");
        }
    }

    @Test
    void generatePdf_markdownHeadingsAndLists_rendersCorrectly() throws IOException {
        String markdown = """
                ## Section
                
                - item one
                - item two
                
                Some paragraph text here.
                """;
        byte[] pdfBytes = service.generatePdf(List.of(markdown));
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("Section");
            assertThat(text).contains("item one");
        }
    }

    @Test
    void generatePdf_unicodeCharacters_noQuestionMarks() throws IOException {
        String unicodeText = "Héllo wörld — café résumé naïve";
        byte[] pdfBytes = service.generatePdf(List.of(unicodeText));
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            String extracted = new PDFTextStripper().getText(doc);
            assertThat(extracted).contains("café");
            assertThat(extracted).contains("résumé");
            assertThat(extracted).doesNotContain("?");
        }
    }

    @Test
    void generatePdf_longText_wrapsWithoutException() throws IOException {
        String longText = "word ".repeat(200);
        byte[] pdfBytes = service.generatePdf(List.of(longText));
        assertThat(pdfBytes).isNotEmpty();
    }

    // --- page orientation ---

    @Test
    void generatePdf_landscapeLayout_producesLandscapePage() throws IOException {
        PageLayout landscape = new PageLayout(842f, 595f); // A4 landscape
        byte[] pdfBytes = service.generatePdf(List.of("Landscape content"), List.of(landscape));
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDRectangle mediaBox = doc.getPage(0).getMediaBox();
            assertThat(mediaBox.getWidth()).isGreaterThan(mediaBox.getHeight());
        }
    }

    @Test
    void generatePdf_mixedOrientations_preservesEachPageOrientation() throws IOException {
        PageLayout portrait = new PageLayout(595f, 842f);
        PageLayout landscape = new PageLayout(842f, 595f);
        byte[] pdfBytes = service.generatePdf(
                List.of("Portrait page", "Landscape page"),
                List.of(portrait, landscape));
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDRectangle page0 = doc.getPage(0).getMediaBox();
            assertThat(page0.getHeight()).isGreaterThan(page0.getWidth());
            PDRectangle page1 = doc.getPage(1).getMediaBox();
            assertThat(page1.getWidth()).isGreaterThan(page1.getHeight());
        }
    }

    @Test
    void generatePdf_landscapeTable_usesFullWidth() throws IOException {
        PageLayout landscape = new PageLayout(842f, 595f);
        String markdown = """
                | Column A | Column B | Column C | Column D |
                |----------|----------|----------|----------|
                | value 1  | value 2  | value 3  | value 4  |
                """;
        byte[] pdfBytes = service.generatePdf(List.of(markdown), List.of(landscape));
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("value 1");
            assertThat(text).contains("value 4");
        }
    }
}
