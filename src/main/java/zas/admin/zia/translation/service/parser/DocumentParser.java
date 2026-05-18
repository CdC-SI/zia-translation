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
     * Returns MIME types supported by this parser (e.g. "application/pdf").
     */
    List<String> supportedMimeTypes();

    /**
     * Extracts the page dimensions from the document.
     *
     * @param documentBytes raw bytes of the source document
     * @return list of page layouts — one entry per page
     * @throws IOException if the document cannot be parsed
     */
    List<PageLayout> extractPageLayouts(byte[] documentBytes) throws IOException;
}
