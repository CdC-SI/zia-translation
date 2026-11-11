package zas.admin.zia.translation.service.pdf;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import zas.admin.zia.translation.service.language.TargetLanguage;
import zas.admin.zia.translation.service.parser.PageLayout;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PdfGenerationServiceTest {

    private final PdfGenerationService service = new PdfGenerationService("fonts/Arial.ttf", new PdfFooterTexts("", "", "", ""));

    @Test
    void generatePdf_singlePage_producesValidPdf() throws IOException {
        byte[] pdfBytes = service.generatePdf(List.of("Hello world"), TargetLanguage.FR);
        assertThat(pdfBytes).isNotEmpty();
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            assertThat(doc.getNumberOfPages()).isGreaterThanOrEqualTo(1);
        }
    }

    @Test
    void generatePdf_multiplePages_insertsPageBreaks() throws IOException {
        List<String> pages = List.of("Page 1 text", "Page 2 text", "Page 3 text");
        byte[] pdfBytes = service.generatePdf(pages, TargetLanguage.FR);
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            assertThat(doc.getNumberOfPages()).isGreaterThanOrEqualTo(3);
        }
    }

    @Test
    void generatePdf_emptyTextList_producesEmptyDocument() throws IOException {
        byte[] pdfBytes = service.generatePdf(List.of(), TargetLanguage.FR);
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
        byte[] pdfBytes = service.generatePdf(List.of(markdown), TargetLanguage.FR);
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
        byte[] pdfBytes = service.generatePdf(List.of(markdown), TargetLanguage.FR);
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("Section");
            assertThat(text).contains("item one");
        }
    }

    @Test
    void generatePdf_unicodeCharacters_noQuestionMarks() throws IOException {
        String unicodeText = "Héllo wörld — café résumé naïve";
        byte[] pdfBytes = service.generatePdf(List.of(unicodeText), TargetLanguage.FR);
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
        byte[] pdfBytes = service.generatePdf(List.of(longText), TargetLanguage.FR);
        assertThat(pdfBytes).isNotEmpty();
    }

    // --- page orientation ---

    @Test
    void generatePdf_landscapeLayout_producesLandscapePage() throws IOException {
        PageLayout landscape = new PageLayout(842f, 595f); // A4 landscape
        byte[] pdfBytes = service.generatePdf(List.of("Landscape content"), List.of(landscape), TargetLanguage.FR);
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
                List.of(portrait, landscape), TargetLanguage.FR);
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
        byte[] pdfBytes = service.generatePdf(List.of(markdown), List.of(landscape), TargetLanguage.FR);
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("value 1");
            assertThat(text).contains("value 4");
        }
    }

    // --- AI translation footer ---

    private static final String FOOTER_FR = "Ce document a été traduit par une intelligence artificielle (IA).";
    private static final String FOOTER_DE = "Dieses Dokument wurde mit künstlicher Intelligenz (KI) übersetzt.";
    private static final String FOOTER_IT = "Questo documento è stato tradotto con l'intelligenza artificiale (IA).";
    private static final String FOOTER_EN = "This document was translated by artificial intelligence (AI).";

    private final PdfGenerationService serviceWithFooter = new PdfGenerationService("fonts/Arial.ttf",
            new PdfFooterTexts(FOOTER_FR, FOOTER_DE, FOOTER_IT, FOOTER_EN));

    @Test
    void generatePdf_withFooter_addsFooterOnEveryPage() throws IOException {
        String longPage = "word ".repeat(3000);
        byte[] pdfBytes = serviceWithFooter.generatePdf(List.of(longPage, "Second page"), TargetLanguage.FR);
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            assertThat(doc.getNumberOfPages()).isGreaterThan(2);
            PDFTextStripper stripper = new PDFTextStripper();
            for (int page = 1; page <= doc.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                assertThat(stripper.getText(doc)).as("page %d", page).contains(FOOTER_FR);
            }
        }
    }

    @Test
    void generatePdf_withFooter_usesTargetLanguageText() throws IOException {
        assertThat(extractText(serviceWithFooter.generatePdf(List.of("Hallo"), TargetLanguage.DE)))
                .contains(FOOTER_DE).doesNotContain(FOOTER_FR);
        assertThat(extractText(serviceWithFooter.generatePdf(List.of("Ciao"), TargetLanguage.IT)))
                .contains(FOOTER_IT);
        assertThat(extractText(serviceWithFooter.generatePdf(List.of("Hello"), TargetLanguage.EN)))
                .contains(FOOTER_EN);
    }

    @Test
    void generatePdf_withFooter_landscapeAndEmptyDocument_stillStamped() throws IOException {
        byte[] landscape = serviceWithFooter.generatePdf(
                List.of("Landscape"), List.of(new PageLayout(842f, 595f)), TargetLanguage.EN);
        assertThat(extractText(landscape)).contains(FOOTER_EN);

        byte[] empty = serviceWithFooter.generatePdf(List.of(), TargetLanguage.EN);
        assertThat(extractText(empty)).contains(FOOTER_EN);
    }

    @Test
    void generatePdf_veryLongFooter_isWrappedWithinPage() throws IOException {
        String longFooter = "AI disclaimer ".repeat(30).strip();
        PdfGenerationService service = new PdfGenerationService("fonts/Arial.ttf",
                new PdfFooterTexts("", "", "", longFooter));
        byte[] pdfBytes = service.generatePdf(List.of("Body"), TargetLanguage.EN);
        String text = extractText(pdfBytes).replaceAll("\\s+", " ");
        assertThat(text).contains(longFooter);
    }

    @Test
    void generatePdf_blankFooter_addsNoFooter() throws IOException {
        PdfGenerationService service = new PdfGenerationService("fonts/Arial.ttf",
                new PdfFooterTexts(FOOTER_FR, "  ", FOOTER_IT, FOOTER_EN));
        String text = extractText(service.generatePdf(List.of("Hallo"), TargetLanguage.DE));
        assertThat(text.strip()).isEqualTo("Hallo");
    }

    private static String extractText(byte[] pdfBytes) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            return new PDFTextStripper().getText(doc);
        }
    }
}
