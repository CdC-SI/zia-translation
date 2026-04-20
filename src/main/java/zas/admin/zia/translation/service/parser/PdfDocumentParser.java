package zas.admin.zia.translation.service.parser;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
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

    private static final String SUPPORTED_MIME_TYPE = "application/pdf";
    private static final float RENDER_DPI = 150f;

    @Override
    public List<byte[]> renderPages(byte[] documentBytes) throws IOException {
        try (PDDocument document = Loader.loadPDF(documentBytes)) {
            PDFRenderer renderer = new PDFRenderer(document);
            int pageCount = document.getNumberOfPages();
            List<byte[]> pages = new ArrayList<>(pageCount);
            for (int i = 0; i < pageCount; i++) {
                BufferedImage image = renderer.renderImageWithDPI(i, RENDER_DPI);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                boolean written = ImageIO.write(image, "PNG", out);
                if (!written) {
                    throw new IOException("No suitable ImageIO writer found for PNG rendering on page " + (i + 1));
                }
                pages.add(out.toByteArray());
            }
            return pages;
        }
    }

    @Override
    public String supportedMimeType() {
        return SUPPORTED_MIME_TYPE;
    }
}
