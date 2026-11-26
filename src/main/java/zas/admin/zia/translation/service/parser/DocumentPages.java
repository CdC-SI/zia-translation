package zas.admin.zia.translation.service.parser;

import zas.admin.zia.translation.service.image.PixelBudget;

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
     * Size of the output page, in PDF points. For PDFs: the crop box, width and height swapped when the page is
     * rotated by 90 or 270 degrees (what the renderer produces). For images: A4, landscape when the upright image
     * is wider than tall (pixel sizes are not points).
     *
     * @param index page index, from 0
     */
    PageLayout layout(int index);

    /**
     * Renders the page as an RGB image whose size fits the budget once padded ({@link PixelBudget#fitPage} for PDF
     * pages, rendered directly at that scale; {@link PixelBudget#fitImage} for images, shrunk, never enlarged).
     * The image is not padded. Nothing is cached: each call renders again.
     *
     * @param index  page index, from 0
     * @param budget pixels of the image sent to the vision model
     * @throws IOException if the page cannot be rendered
     */
    BufferedImage render(int index, PixelBudget budget) throws IOException;

    @Override
    void close() throws IOException;
}
