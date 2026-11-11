package zas.admin.zia.translation.service.parser;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Component
class PdfDocumentParser implements DocumentParser {

    private static final List<String> SUPPORTED_MIME_TYPES = List.of("application/pdf");
    private static final float RENDER_DPI = 150f;

    @Override
    public List<byte[]> renderPages(byte[] documentBytes) throws IOException {
        return renderPages(documentBytes, Integer.MAX_VALUE).pages();
    }

    @Override
    public RenderedPages renderPages(byte[] documentBytes, int maxPages) throws IOException {
        try (PDDocument document = Loader.loadPDF(documentBytes)) {
            PDFRenderer renderer = new PDFRenderer(document);
            int pageCount = document.getNumberOfPages();
            int toRender = Math.clamp(maxPages, 0, pageCount);
            List<byte[]> pages = new ArrayList<>(toRender);
            for (int i = 0; i < toRender; i++) {
                BufferedImage image = renderer.renderImageWithDPI(i, RENDER_DPI);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                boolean written = ImageIO.write(image, "png", out);
                if (!written) {
                    throw new IOException("No suitable ImageIO writer found for PNG rendering on page " + (i + 1));
                }
                pages.add(out.toByteArray());
            }
            return new RenderedPages(pages, pageCount);
        }
    }

    @Override
    public List<String> supportedMimeTypes() {
        return SUPPORTED_MIME_TYPES;
    }

    @Override
    public List<PageLayout> extractPageLayouts(byte[] documentBytes) throws IOException {
        try (PDDocument document = Loader.loadPDF(documentBytes)) {
            int pageCount = document.getNumberOfPages();
            List<PageLayout> layouts = new ArrayList<>(pageCount);
            for (int i = 0; i < pageCount; i++) {
                PDRectangle mediaBox = document.getPage(i).getMediaBox();
                layouts.add(new PageLayout(mediaBox.getWidth(), mediaBox.getHeight()));
            }
            return layouts;
        }
    }
}
