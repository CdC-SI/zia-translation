package zas.admin.zia.translation.service.parser;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import zas.admin.zia.translation.service.InvalidDocumentException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.OutputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PdfDocumentParserTest {

    private final PdfDocumentParser parser = new PdfDocumentParser();

    @Test
    void validate_pdfWithJpeg2000Image_rejected() throws IOException {
        byte[] pdf = pdfWithImage(COSName.JPX_DECODE, false);

        assertThatThrownBy(() -> parser.validate(pdf))
                .isInstanceOf(InvalidDocumentException.class)
                .hasMessageContaining("JPEG 2000");
    }

    @Test
    void validate_jpeg2000ImageNestedInFormXObject_rejected() throws IOException {
        assertThatThrownBy(() -> parser.validate(pdfWithImage(COSName.JPX_DECODE, true)))
                .isInstanceOf(InvalidDocumentException.class);
    }

    @Test
    void validate_jpeg2000InFilterArray_rejected() throws IOException {
        COSArray filters = new COSArray();
        filters.add(COSName.FLATE_DECODE);
        filters.add(COSName.JPX_DECODE);

        assertThatThrownBy(() -> parser.validate(pdfWithImage(filters, false)))
                .isInstanceOf(InvalidDocumentException.class);
    }

    @Test
    void validate_pdfWithOtherImages_accepted() throws IOException {
        parser.validate(pdfWithImage(COSName.DCT_DECODE, false));
        parser.validate(pdfWithImage(COSName.JBIG2_DECODE, true));
        parser.validate(createMinimalPdf(2));
    }

    @Test
    void validate_unreadableBytes_leftToRendering() {
        parser.validate("%PDF-1.7 truncated".getBytes());
    }

    @Test
    void jbig2DecoderIsOnTheClasspath() {
        // PDFBox renders JBIG2 scans only when this ImageIO plugin is present (otherwise: blank image, warning)
        assertThat(ImageIO.getImageReadersByFormatName("JBIG2").hasNext()).isTrue();
    }

    /** A one-page PDF with a 1x1 image XObject using the given filter (not decodable: only the dictionary matters). */
    private static byte[] pdfWithImage(COSBase filter, boolean nestedInForm) throws IOException {
        try (PDDocument document = new PDDocument()) {
            COSStream image = document.getDocument().createCOSStream();
            image.setItem(COSName.TYPE, COSName.XOBJECT);
            image.setItem(COSName.SUBTYPE, COSName.IMAGE);
            image.setItem(COSName.FILTER, filter);
            image.setInt(COSName.WIDTH, 1);
            image.setInt(COSName.HEIGHT, 1);
            image.setInt(COSName.BITS_PER_COMPONENT, 8);
            try (OutputStream out = image.createRawOutputStream()) {
                out.write(new byte[]{0});
            }
            COSStream target = image;
            if (nestedInForm) {
                COSStream form = document.getDocument().createCOSStream();
                form.setItem(COSName.TYPE, COSName.XOBJECT);
                form.setItem(COSName.SUBTYPE, COSName.FORM);
                form.setItem(COSName.RESOURCES, xObjects("Im1", image));
                try (OutputStream out = form.createOutputStream()) {
                    out.write("/Im1 Do".getBytes());
                }
                target = form;
            }
            PDPage page = new PDPage(PDRectangle.A4);
            page.setResources(new PDResources(xObjects("X1", target)));
            document.addPage(page);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private static COSDictionary xObjects(String name, COSStream stream) {
        COSDictionary xObjects = new COSDictionary();
        xObjects.setItem(COSName.getPDFName(name), stream);
        COSDictionary resources = new COSDictionary();
        resources.setItem(COSName.XOBJECT, xObjects);
        return resources;
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
