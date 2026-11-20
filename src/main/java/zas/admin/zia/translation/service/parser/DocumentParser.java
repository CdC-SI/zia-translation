package zas.admin.zia.translation.service.parser;

import java.io.IOException;
import java.util.List;

public interface DocumentParser {

    /**
     * Opens the document once; its pages are then rendered on demand. The caller must close the result.
     *
     * @param documentBytes raw bytes of the source document
     * @throws IOException if the document cannot be read
     */
    DocumentPages open(byte[] documentBytes) throws IOException;

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
}
