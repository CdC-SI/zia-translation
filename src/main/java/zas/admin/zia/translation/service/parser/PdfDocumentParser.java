package zas.admin.zia.translation.service.parser;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;
import zas.admin.zia.translation.service.image.PixelBudget;

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

    /**
     * Scale (pixels per point) to render a page of this layout at, so that the image fits the budget. PDFBox sizes
     * the image {@code floor(points x scale)} in float arithmetic, which can exceed the fitted size by one pixel when
     * the scale is rounded to a float: the scale is lowered to the float just below until it does not.
     */
    static float renderScale(PageLayout layout, PixelBudget budget) {
        PixelBudget.Size size = budget.fitPage(layout.widthPt(), layout.heightPt());
        float scale = (float) size.scale();
        while (renderedSide(layout.widthPt(), scale) > size.width()
                || renderedSide(layout.heightPt(), scale) > size.height()) {
            scale = Math.nextDown(scale);
        }
        return scale;
    }

    /** Side of the image PDFBox renders (PDFRenderer.renderImage: floor of points x scale, at least 1). */
    static int renderedSide(float points, float scale) {
        return (int) Math.max(Math.floor(points * scale), 1);
    }

    /** A loaded PDF; pages rendered on demand, directly at the scale of the budget (no resize afterwards). */
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
        public synchronized BufferedImage render(int index, PixelBudget budget) throws IOException {
            return renderer.renderImage(index, renderScale(layout(index), budget));
        }

        @Override
        public synchronized void close() throws IOException {
            document.close();
        }
    }
}
