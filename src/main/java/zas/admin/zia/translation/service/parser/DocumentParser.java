package zas.admin.zia.translation.service.parser;

import java.io.IOException;
import java.util.List;

public interface DocumentParser {

    /**
     * Parses the given document bytes and returns the rendered content per page
     * as raw PNG image bytes, ready for vision-model processing.
     *
     * @param documentBytes raw bytes of the source document
     * @return list of PNG image bytes — one entry per page
     * @throws IOException if the document cannot be parsed
     */
    List<byte[]> renderPages(byte[] documentBytes) throws IOException;

    /**
     * Renders at most the first {@code maxPages} pages of the document as PNG images.
     *
     * @param documentBytes raw bytes of the source document
     * @param maxPages      maximum number of pages to render
     * @return the rendered pages and the page count of the document
     * @throws IOException if the document cannot be parsed
     */
    default RenderedPages renderPages(byte[] documentBytes, int maxPages) throws IOException {
        List<byte[]> pages = renderPages(documentBytes);
        return new RenderedPages(pages.subList(0, Math.clamp(maxPages, 0, pages.size())), pages.size());
    }

    /**
     * Returns the MIME types supported by this parser, as detected from the document bytes (e.g. "application/pdf").
     */
    List<String> supportedMimeTypes();

    /**
     * Checks, before any job is created, that a document of a supported type can be processed. Rejects only what
     * is positively identified as unprocessable; unreadable documents are left to fail later in rendering.
     *
     * @param documentBytes raw bytes of the source document
     * @throws zas.admin.zia.translation.service.InvalidDocumentException if the document cannot be processed
     */
    default void validate(byte[] documentBytes) {
        // nothing to check by default
    }

    /**
     * Extracts the page dimensions from the document.
     *
     * @param documentBytes raw bytes of the source document
     * @return list of page layouts — one entry per page
     * @throws IOException if the document cannot be parsed
     */
    List<PageLayout> extractPageLayouts(byte[] documentBytes) throws IOException;
}
