package zas.admin.zia.translation.service.parser;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;

/**
 * PDF uploads, rendered with PDFBox. Scanned pages compressed with JBIG2 or JPEG 2000 are decoded by the
 * jbig2-imageio and jai-imageio-jpeg2000 plugins (without them PDFBox draws those images blank).
 */
@Component
class PdfDocumentParser implements DocumentParser {

    private static final List<String> SUPPORTED_MIME_TYPES = List.of("application/pdf");
    private static final float RENDER_DPI = 150f;

    static {
        ImageIoPlugins.removeJaiImageIoCorePlugins();
    }

    @Override
    public DocumentPages open(byte[] documentBytes) throws IOException {
        return new PdfPages(Loader.loadPDF(documentBytes));
    }

    @Override
    public List<String> supportedMimeTypes() {
        return SUPPORTED_MIME_TYPES;
    }

    /** A loaded PDF; pages rendered at {@value #RENDER_DPI} DPI on demand. */
    private static final class PdfPages implements DocumentPages {

        private final PDDocument document;
        private final PDFRenderer renderer;

        PdfPages(PDDocument document) {
            this.document = document;
            this.renderer = new PDFRenderer(document);
        }

        @Override
        public int pageCount() {
            return document.getNumberOfPages();
        }

        @Override
        public synchronized PageLayout layout(int index) {
            PDPage page = document.getPage(index);
            PDRectangle cropBox = page.getCropBox();
            boolean quarterTurn = Math.floorMod(page.getRotation(), 180) == 90;
            return quarterTurn
                    ? new PageLayout(cropBox.getHeight(), cropBox.getWidth())
                    : new PageLayout(cropBox.getWidth(), cropBox.getHeight());
        }

        @Override
        public synchronized BufferedImage render(int index) throws IOException {
            return renderer.renderImageWithDPI(index, RENDER_DPI);
        }

        @Override
        public synchronized void close() throws IOException {
            document.close();
        }
    }
}
