package zas.admin.zia.translation.service.parser;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import zas.admin.zia.translation.service.InvalidDocumentException;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

@Component
class PdfDocumentParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(PdfDocumentParser.class);
    private static final List<String> SUPPORTED_MIME_TYPES = List.of("application/pdf");
    private static final float RENDER_DPI = 150f;

    /** PDFs rejected because of JPEG 2000 images since startup, to measure how often it happens. */
    private final AtomicLong jpxRejections = new AtomicLong();

    @Override
    public DocumentPages open(byte[] documentBytes) throws IOException {
        return new PdfPages(Loader.loadPDF(documentBytes));
    }

    @Override
    public List<String> supportedMimeTypes() {
        return SUPPORTED_MIME_TYPES;
    }

    /**
     * Rejects PDFs containing JPEG 2000 (JPXDecode) images. No JPEG 2000 decoder is on the classpath (the
     * jai-imageio-jpeg2000 licence is not approved), so PDFBox would silently skip those images and send a blank
     * page to the model. Unreadable PDFs are left to fail in rendering.
     */
    @Override
    public void validate(byte[] documentBytes) {
        boolean containsJpx;
        try (PDDocument document = Loader.loadPDF(documentBytes)) {
            containsJpx = containsJpxImage(document);
        } catch (IOException unreadable) {
            return;
        }
        if (containsJpx) {
            log.warn("Rejected a PDF containing JPEG 2000 images ({} since startup)", jpxRejections.incrementAndGet());
            throw new InvalidDocumentException(
                    "This PDF contains JPEG 2000 images, which are not supported. Please export it with another image compression.");
        }
    }

    /** Walks the image XObjects of every page, including those nested in form XObjects, without decoding them. */
    static boolean containsJpxImage(PDDocument document) {
        Set<COSBase> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (PDPage page : document.getPages()) {
            PDResources resources = page.getResources();
            if (resources != null && containsJpxImage(resources.getCOSObject(), visited)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsJpxImage(COSDictionary resources, Set<COSBase> visited) {
        if (resources == null || !visited.add(resources)) {
            return false;
        }
        COSDictionary xObjects = resources.getCOSDictionary(COSName.XOBJECT);
        if (xObjects == null) {
            return false;
        }
        for (COSName name : xObjects.keySet()) {
            if (!(xObjects.getDictionaryObject(name) instanceof COSStream stream) || !visited.add(stream)) {
                continue;
            }
            COSName subtype = stream.getCOSName(COSName.SUBTYPE);
            if (COSName.IMAGE.equals(subtype)
                    && (usesJpx(stream) || usesJpx(stream.getCOSStream(COSName.SMASK)))) {
                return true;
            }
            if (COSName.FORM.equals(subtype)
                    && containsJpxImage(stream.getCOSDictionary(COSName.RESOURCES), visited)) {
                return true;
            }
        }
        return false;
    }

    private static boolean usesJpx(COSStream stream) {
        if (stream == null) {
            return false;
        }
        COSBase filter = stream.getDictionaryObject(COSName.FILTER);
        if (filter instanceof COSArray filters) {
            for (COSBase entry : filters) {
                COSBase value = entry instanceof COSObject reference ? reference.getObject() : entry;
                if (COSName.JPX_DECODE.equals(value)) {
                    return true;
                }
            }
            return false;
        }
        return COSName.JPX_DECODE.equals(filter);
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
