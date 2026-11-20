package zas.admin.zia.translation.service.parser;

import java.awt.image.BufferedImage;
import java.io.IOException;

/**
 * An opened document whose pages are rendered on demand, one at a time, so that a caller never holds more page
 * images than it is processing. Implementations are safe to call from several threads ({@link #render} is
 * serialized: PDFBox is not thread-safe). Must be closed.
 */
public interface DocumentPages extends AutoCloseable {

    int pageCount();

    /**
     * Size of the page as displayed, in PDF points: for PDFs the crop box, width and height swapped when the page
     * is rotated by 90 or 270 degrees (what the renderer produces).
     *
     * @param index page index, from 0
     */
    PageLayout layout(int index);

    /**
     * Renders the page as an RGB image. Nothing is cached: each call renders again.
     *
     * @param index page index, from 0
     * @throws IOException if the page cannot be rendered
     */
    BufferedImage render(int index) throws IOException;

    @Override
    void close() throws IOException;
}
