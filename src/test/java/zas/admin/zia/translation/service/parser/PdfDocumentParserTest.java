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

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PdfDocumentParserTest {

    private final PdfDocumentParser parser = new PdfDocumentParser();

    @Test
    void render_jpeg2000Image_isDecoded() throws IOException {
        // a black square drawn at (100, 100)-(300, 300) pt, as a JPXDecode image like "high compression" scans
        try (DocumentPages pages = parser.open(pdfWithBlackJpeg2000Square())) {
            BufferedImage page = pages.render(0);

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

    /** Gray level (0 black, 255 white) at a point given in PDF points from the bottom-left of an A4 page at 150 DPI. */
    private static int gray(BufferedImage page, float xPt, float yPt) {
        int x = Math.round(xPt * 150 / 72);
        int y = Math.round((PDRectangle.A4.getHeight() - yPt) * 150 / 72);
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
    void supportedMimeTypes_returnsPdf() {
        assertThat(parser.supportedMimeTypes()).containsExactly("application/pdf");
    }

    @Test
    void open_rendersPagesOnDemandAsRgbImages() throws IOException {
        try (DocumentPages pages = parser.open(createMinimalPdf(3))) {
            assertThat(pages.pageCount()).isEqualTo(3);

            BufferedImage page = pages.render(2);

            // US Letter (612 x 792 pt) at 150 DPI, no PNG encoding in between; PDFBox truncates 1649.99 to 1649
            assertThat(page.getType()).isEqualTo(BufferedImage.TYPE_INT_RGB);
            assertThat(page.getWidth()).isEqualTo(1275);
            assertThat(page.getHeight()).isEqualTo(1649);
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
            BufferedImage rendered = pages.render(0);

            assertThat(pages.layout(0)).isEqualTo(new PageLayout(495f, 742f));
            // the renderer draws the same crop box: 495 x 742 pt at 150 DPI (PDFBox rounds down)
            assertThat(rendered.getWidth()).isEqualTo(1031);
            assertThat(rendered.getHeight()).isEqualTo(1545);
        }
    }

    @Test
    void layout_rotatedPage_widthAndHeightSwappedLikeTheRenderer() throws IOException {
        PDPage page = new PDPage(PDRectangle.A4);
        page.setRotation(90);

        try (DocumentPages pages = parser.open(pdfOf(page))) {
            PageLayout layout = pages.layout(0);
            BufferedImage rendered = pages.render(0);

            assertThat(layout.widthPt()).isEqualTo(PDRectangle.A4.getHeight());
            assertThat(layout.heightPt()).isEqualTo(PDRectangle.A4.getWidth());
            assertThat(rendered.getWidth()).isGreaterThan(rendered.getHeight());
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
                    renders.add(executor.submit(() -> pages.render(index)));
                }
            }
            for (var render : renders) {
                assertThat(render.get().getWidth()).isEqualTo(1275);
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
