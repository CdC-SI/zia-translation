package zas.admin.zia.translation.service.parser;

import org.apache.tika.Tika;
import zas.admin.zia.translation.service.InvalidDocumentException;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Chooses the parser of a document from its <b>bytes</b> (magic numbers, via Apache Tika), never from the
 * Content-Type declared by the client: that label is guessed from the file extension and is often missing or
 * {@code application/octet-stream}. Shared by the translation and extraction services.
 */
public final class DocumentParserResolver {

    private static final Tika TIKA = new Tika();
    private static final String PDF_MIME_TYPE = "application/pdf";
    private static final byte[] PDF_HEADER = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    /** PDF readers accept the header anywhere in the first 1024 bytes; Tika only looks at the very start. */
    private static final int PDF_HEADER_SEARCH_LIMIT = 1024;
    private static final String UNKNOWN_TYPE = "application/octet-stream";
    private static final Set<String> UNSUPPORTED_PHOTO_TYPES = Set.of(
            "image/heic", "image/heic-sequence", "image/heif", "image/heif-sequence", "image/avif");

    private final Map<String, DocumentParser> parsersByMimeType;

    public DocumentParserResolver(List<DocumentParser> parsers) {
        this.parsersByMimeType = buildParsersByMimeType(parsers);
    }

    /**
     * Returns the parser of the document, after the parser has checked that it can process it.
     *
     * @throws InvalidDocumentException unknown or unsupported format, or a document the parser rejects
     *                                  (e.g. image too large, PDF with JPEG 2000 images)
     */
    public DocumentParser resolve(byte[] documentBytes) {
        String mimeType = detectMimeType(documentBytes);
        if (UNSUPPORTED_PHOTO_TYPES.contains(mimeType)) {
            throw new InvalidDocumentException(
                    "HEIC/HEIF/AVIF images are not supported. Please export the photo as JPEG or PDF and upload it again.");
        }
        DocumentParser parser = parsersByMimeType.get(mimeType);
        if (parser == null) {
            String detected = UNKNOWN_TYPE.equals(mimeType)
                    ? "Unrecognized document format"
                    : "Unsupported document format '%s'".formatted(mimeType);
            throw new InvalidDocumentException("%s. Supported formats: %s."
                    .formatted(detected, String.join(", ", parsersByMimeType.keySet())));
        }
        parser.validate(documentBytes);
        return parser;
    }

    /**
     * Detects the MIME type from the content only ({@code application/octet-stream} when unknown).
     */
    public static String detectMimeType(byte[] documentBytes) {
        return hasPdfHeader(documentBytes) ? PDF_MIME_TYPE : TIKA.detect(documentBytes);
    }

    private static boolean hasPdfHeader(byte[] bytes) {
        int limit = Math.min(bytes.length, PDF_HEADER_SEARCH_LIMIT) - PDF_HEADER.length;
        for (int offset = 0; offset <= limit; offset++) {
            if (Arrays.equals(bytes, offset, offset + PDF_HEADER.length, PDF_HEADER, 0, PDF_HEADER.length)) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, DocumentParser> buildParsersByMimeType(List<DocumentParser> parsers) {
        Map<String, DocumentParser> parserMap = new LinkedHashMap<>();
        for (DocumentParser parser : parsers) {
            for (String mimeType : parser.supportedMimeTypes()) {
                DocumentParser previous = parserMap.putIfAbsent(mimeType, parser);
                if (previous != null && previous != parser) {
                    throw new IllegalStateException("Duplicate parser mapping for MIME type '%s'.".formatted(mimeType));
                }
            }
        }
        return Collections.unmodifiableMap(parserMap);  // keeps insertion order for the error message
    }
}
