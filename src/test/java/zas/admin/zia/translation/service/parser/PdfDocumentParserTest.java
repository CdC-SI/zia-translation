package zas.admin.zia.translation.service.parser;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceGray;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;
import zas.admin.zia.translation.service.image.PixelBudget;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PdfDocumentParserTest {

    private static final PixelBudget BUDGET = new PixelBudget(1_000_000);

    private final PdfDocumentParser parser = new PdfDocumentParser();

    @Test
    void render_jpeg2000Image_isDecoded() throws IOException {
        // a black square drawn at (100, 100)-(300, 300) pt, as a JPXDecode image like "high compression" scans
        try (DocumentPages pages = parser.open(pdfWithBlackJpeg2000Square())) {
            BufferedImage page = pages.render(0, BUDGET);

            assertThat(gray(page, 200, 200)).isLessThan(40);
            assertThat(gray(page, 400, 600)).isGreaterThan(215);
        }
    }

    @Test
    void validate_pdfWithJpeg2000Image_accepted() throws IOException {
        parser.validate(pdfWithBlackJpeg2000Square());
    }

    @Test
    void jbig2AndJpeg2000DecodersAreOnTheClasspath() {
        // PDFBox renders JBIG2 and JPEG 2000 scans only when these ImageIO plugins are present (otherwise: blank image)
        assertThat(ImageIO.getImageReadersByFormatName("JBIG2").hasNext()).isTrue();
        assertThat(ImageIO.getImageReadersByFormatName("JPEG2000").hasNext()).isTrue();
    }

    /** Gray level (0 black, 255 white) at a point given in PDF points from the bottom-left of a rendered A4 page. */
    private static int gray(BufferedImage page, float xPt, float yPt) {
        double scale = page.getWidth() / (double) PDRectangle.A4.getWidth();
        int x = (int) Math.round(xPt * scale);
        int y = (int) Math.round((PDRectangle.A4.getHeight() - yPt) * scale);
        return page.getRaster().getSample(x, y, 1);
    }

    private static byte[] pdfWithBlackJpeg2000Square() throws IOException {
        BufferedImage black = new BufferedImage(64, 64, BufferedImage.TYPE_BYTE_GRAY);
        ByteArrayOutputStream jpx = new ByteArrayOutputStream();
        assertThat(ImageIO.write(black, "JPEG2000", jpx)).isTrue();
        try (PDDocument document = new PDDocument()) {
            PDImageXObject image = new PDImageXObject(document, new ByteArrayInputStream(jpx.toByteArray()),
                    COSName.JPX_DECODE, 64, 64, 8, PDDeviceGray.INSTANCE);
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.drawImage(image, 100, 100, 200, 200);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void render_a4AtTheBudget_sentWithoutResize() throws IOException {
        try (DocumentPages pages = parser.open(pdfOf(new PDPage(PDRectangle.A4)))) {
            BufferedImage page = pages.render(0, BUDGET);

            // fitted 832 x 1178 (~100 DPI); PDFBox may render a pixel less (float), padding absorbs it
            assertRenderedAtTheBudget(page, pages.layout(0));
            assertThat(BUDGET.fits(page.getWidth(), page.getHeight())).isTrue();
        }
    }

    @Test
    void renderScale_neverRendersAboveTheFittedSize() {
        java.util.Random random = new java.util.Random(7);
        for (int i = 0; i < 2000; i++) {
            PageLayout layout = new PageLayout(50 + random.nextFloat() * 2000, 50 + random.nextFloat() * 2000);
            PixelBudget budget = new PixelBudget(1024 + random.nextInt(3_000_000));
            PixelBudget.Size size = budget.fitPage(layout.widthPt(), layout.heightPt());

            float scale = PdfDocumentParser.renderScale(layout, budget);

            assertThat(PdfDocumentParser.renderedSide(layout.widthPt(), scale)).isBetween(size.width() - 1, size.width());
            assertThat(PdfDocumentParser.renderedSide(layout.heightPt(), scale)).isBetween(size.height() - 1, size.height());
        }
    }

    /** The page is the fitted size of its layout, or a pixel less (float scale), so it fits the budget once padded. */
    private static void assertRenderedAtTheBudget(BufferedImage page, PageLayout layout) {
        PixelBudget.Size size = BUDGET.fitPage(layout.widthPt(), layout.heightPt());
        assertThat(page.getWidth()).isBetween(size.width() - 1, size.width());
        assertThat(page.getHeight()).isBetween(size.height() - 1, size.height());
    }

    @Test
    void supportedMimeTypes_returnsPdf() {
        assertThat(parser.supportedMimeTypes()).containsExactly("application/pdf");
    }

    @Test
    void open_rendersPagesOnDemandAsRgbImages() throws IOException {
        try (DocumentPages pages = parser.open(createMinimalPdf(3))) {
            assertThat(pages.pageCount()).isEqualTo(3);

            BufferedImage page = pages.render(2, BUDGET);

            // US Letter (612 x 792 pt) rendered directly at the budget: 864 x 1119 (~102 DPI), sent as 864 x 1120
            assertThat(page.getType()).isEqualTo(BufferedImage.TYPE_INT_RGB);
            assertRenderedAtTheBudget(page, pages.layout(2));
            assertThat(page.getWidth()).isBetween(863, 864);
            assertThat(page.getHeight()).isBetween(1118, 1119);
        }
    }

    @Test
    void layout_returnsPageSizeInPoints() throws IOException {
        try (DocumentPages pages = parser.open(createMinimalPdf(2))) {
            // Default PDPage uses US Letter (612 x 792)
            assertThat(pages.layout(0)).isEqualTo(new PageLayout(612f, 792f));
            assertThat(pages.layout(1)).isEqualTo(new PageLayout(612f, 792f));
        }
    }

    @Test
    void layout_landscapePage_detectsLandscape() throws IOException {
        try (DocumentPages pages = parser.open(createLandscapePdf())) {
            assertThat(pages.layout(0).isLandscape()).isTrue();
        }
    }

    @Test
    void layout_usesTheCropBoxLikeTheRenderer() throws IOException {
        PDPage page = new PDPage(PDRectangle.A4);
        page.setCropBox(new PDRectangle(50, 50, 495, 742));

        try (DocumentPages pages = parser.open(pdfOf(page))) {
            BufferedImage rendered = pages.render(0, BUDGET);

            assertThat(pages.layout(0)).isEqualTo(new PageLayout(495f, 742f));
            // the renderer draws the same crop box, so the budget is computed on the size it renders
            assertRenderedAtTheBudget(rendered, pages.layout(0));
        }
    }

    @Test
    void layout_rotatedPage_widthAndHeightSwappedLikeTheRenderer() throws IOException {
        PDPage page = new PDPage(PDRectangle.A4);
        page.setRotation(90);

        try (DocumentPages pages = parser.open(pdfOf(page))) {
            PageLayout layout = pages.layout(0);
            BufferedImage rendered = pages.render(0, BUDGET);

            assertThat(layout.widthPt()).isEqualTo(PDRectangle.A4.getHeight());
            assertThat(layout.heightPt()).isEqualTo(PDRectangle.A4.getWidth());
            assertThat(rendered.getWidth()).isGreaterThan(rendered.getHeight());
            assertRenderedAtTheBudget(rendered, layout);
        }
    }

    @Test
    void render_concurrentCalls_areSerialized() throws Exception {
        try (DocumentPages pages = parser.open(createMinimalPdf(4));
             var executor = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            List<java.util.concurrent.Future<BufferedImage>> renders = new java.util.ArrayList<>();
            for (int round = 0; round < 3; round++) {
                for (int i = 0; i < 4; i++) {
                    int index = i;
                    renders.add(executor.submit(() -> pages.render(index, BUDGET)));
                }
            }
            for (var render : renders) {
                assertThat(render.get().getWidth()).isBetween(863, 864);
            }
        }
    }

    @Test
    void open_unreadableBytes_throwsIOException() {
        assertThatThrownBy(() -> parser.open("%PDF-1.7 truncated".getBytes())).isInstanceOf(IOException.class);
    }

    private static byte[] pdfOf(PDPage page) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(page);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
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

    private static byte[] createLandscapePdf() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth()));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.beginText();
                cs.newLineAtOffset(50, 400);
                cs.showText("Landscape page");
                cs.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }
}
