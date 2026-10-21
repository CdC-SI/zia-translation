package zas.admin.zia.translation.service.extraction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;
import zas.admin.zia.translation.service.InvalidDocumentException;
import zas.admin.zia.translation.service.parser.DocumentParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Asynchronous extraction of values from a document (PDF or image), page by page with the vision model.
 */
@Service
public class ExtractionService {

    private static final Logger log = LoggerFactory.getLogger(ExtractionService.class);

    static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;
    static final String PDF_MIME_TYPE = "application/pdf";
    private static final byte[] PDF_HEADER = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    private static final int PDF_HEADER_SEARCH_LIMIT = 1024;

    private final ExtractionJobStore jobStore;
    private final ExtractionRequestResolver requestResolver;
    private final VisionFieldExtractor fieldExtractor;
    private final List<DocumentParser> parsers;
    private final Executor executor;
    private final long maxFileSizeBytes;
    private final int maxPages;

    ExtractionService(
            ExtractionJobStore jobStore,
            ExtractionRequestResolver requestResolver,
            VisionFieldExtractor fieldExtractor,
            List<DocumentParser> parsers,
            @Qualifier("translationTaskExecutor") Executor executor,
            @Value("${zia.translation.pdf.max-file-size}") DataSize maxFileSize,
            @Value("${zia.extraction.max-pages:20}") int maxPages) {
        this.jobStore = jobStore;
        this.requestResolver = requestResolver;
        this.fieldExtractor = fieldExtractor;
        this.parsers = List.copyOf(parsers);
        this.executor = executor;
        this.maxFileSizeBytes = maxFileSize.toBytes();
        this.maxPages = maxPages;
    }

    /**
     * Creates the job of the submission (or returns the existing job of the {@code Idempotency-Key}).
     *
     * @throws InvalidDocumentException            invalid key, document or context
     * @throws DocumentTooLargeException           document larger than the configured maximum
     * @throws ExtractionCapacityExceededException no capacity left to process the job
     */
    public ExtractionJobResponse submit(String idempotencyKey, MultipartFile file, ExtractionContext context)
            throws IOException {
        validateIdempotencyKey(idempotencyKey);
        ExtractionRequest request = requestResolver.resolve(context);
        byte[] document = readDocument(file);
        DocumentParser parser = selectParser(document, file.getContentType());

        ExtractionJobStore.Submission submission = jobStore.submit(idempotencyKey);
        ExtractionJob job = submission.job();
        if (!submission.created()) {
            log.info("Extraction already submitted for key '{}': job {}", sanitize(idempotencyKey), job.jobId());
            return ExtractionJobResponse.of(job);
        }
        try {
            executor.execute(() -> process(job.jobId(), document, parser, request));
        } catch (RejectedExecutionException ex) {
            jobStore.delete(job.jobId());
            throw new ExtractionCapacityExceededException("Too many extractions in progress, retry later.", ex);
        }
        log.info("Extraction job {} submitted for document '{}' (profile {}, {} fields, {} collections)",
                job.jobId(), sanitize(request.documentId()), request.profile(),
                request.fields().size(), request.collections().size());
        return ExtractionJobResponse.of(job);
    }

    public Optional<ExtractionJobResponse> find(String jobId) {
        return jobStore.findById(jobId).map(ExtractionJobResponse::of);
    }

    void process(String jobId, byte[] document, DocumentParser parser, ExtractionRequest request) {
        jobStore.markRunning(jobId);
        try {
            jobStore.markSucceeded(jobId, extract(document, parser, request));
            log.info("Extraction job {} succeeded", jobId);
        } catch (UnreadableDocumentException ex) {
            log.warn("Extraction job {} failed: unreadable document", jobId, ex);
            jobStore.markFailed(jobId, new ExtractionError(ExtractionError.UNREADABLE_DOCUMENT, ex.getMessage()));
        } catch (AllPagesFailedException ex) {
            log.error("Extraction job {} failed: no page could be extracted", jobId, ex);
            jobStore.markFailed(jobId, new ExtractionError(ExtractionError.EXTRACTION_FAILED, ex.getMessage()));
        } catch (RuntimeException ex) {
            log.error("Extraction job {} failed", jobId, ex);
            jobStore.markFailed(jobId, new ExtractionError(ExtractionError.INTERNAL_ERROR, "Unexpected extraction error."));
        }
    }

    private ExtractionResponse extract(byte[] document, DocumentParser parser, ExtractionRequest request) {
        List<byte[]> pages;
        try {
            pages = parser.renderPages(document);
        } catch (IOException | RuntimeException ex) {
            throw new UnreadableDocumentException("The document cannot be read.", ex);
        }
        if (pages.isEmpty()) {
            throw new UnreadableDocumentException("The document has no page.", null);
        }

        List<String> warnings = new ArrayList<>(request.warnings());
        int pageCount = pages.size();
        if (pageCount > maxPages) {
            warnings.add("Only the first %d of %d pages were analysed".formatted(maxPages, pageCount));
            pages = pages.subList(0, maxPages);
        }

        List<PageExtraction> extractions = new ArrayList<>(pages.size());
        RuntimeException lastFailure = null;
        for (int index = 0; index < pages.size(); index++) {
            int page = index + 1;
            try {
                extractions.add(fieldExtractor.extract(pages.get(index), page, pageCount, request));
            } catch (RuntimeException ex) {
                log.warn("Extraction of page {} failed", page, ex);
                warnings.add("Page %d could not be analysed by the extraction service".formatted(page));
                lastFailure = ex;
            }
        }
        if (extractions.isEmpty()) {
            throw new AllPagesFailedException("No page of the document could be analysed.", lastFailure);
        }
        return ExtractionResultMerger.merge(request, extractions, warnings);
    }

    private static void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new InvalidDocumentException("Header 'Idempotency-Key' is missing or empty.");
        }
        if (idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new InvalidDocumentException(
                    "Header 'Idempotency-Key' exceeds %d characters.".formatted(MAX_IDEMPOTENCY_KEY_LENGTH));
        }
    }

    private byte[] readDocument(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new InvalidDocumentException("Part 'file' is missing or empty.");
        }
        if (file.getSize() > maxFileSizeBytes) {
            throw new DocumentTooLargeException(
                    "File size (%d bytes) exceeds maximum allowed (%d bytes).".formatted(file.getSize(), maxFileSizeBytes));
        }
        return file.getBytes();
    }

    /**
     * Selects the parser from the content of the document (PDF, PNG, JPEG, GIF or BMP signature) or, when the
     * content is not recognized, from its declared content type.
     */
    DocumentParser selectParser(byte[] document, String declaredContentType) {
        String mimeType = detectMimeType(document);
        if (mimeType == null && declaredContentType != null && !PDF_MIME_TYPE.equals(declaredContentType)) {
            mimeType = declaredContentType;
        }
        String resolved = mimeType;
        return parsers.stream()
                .filter(parser -> resolved != null && parser.supportedMimeTypes().contains(resolved))
                .findFirst()
                .orElseThrow(() -> new InvalidDocumentException(
                        "Unsupported document type: only PDF and images (PNG, JPEG, GIF, BMP) are accepted."));
    }

    static String detectMimeType(byte[] bytes) {
        if (hasPdfHeader(bytes)) {
            return PDF_MIME_TYPE;
        }
        if (startsWith(bytes, 0, new byte[]{(byte) 0x89, 'P', 'N', 'G'})) {
            return "image/png";
        }
        if (startsWith(bytes, 0, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})) {
            return "image/jpeg";
        }
        if (startsWith(bytes, 0, "GIF8".getBytes(StandardCharsets.US_ASCII))) {
            return "image/gif";
        }
        if (startsWith(bytes, 0, "BM".getBytes(StandardCharsets.US_ASCII))) {
            return "image/bmp";
        }
        return null;
    }

    static boolean hasPdfHeader(byte[] bytes) {
        int limit = Math.min(bytes.length, PDF_HEADER_SEARCH_LIMIT) - PDF_HEADER.length;
        for (int offset = 0; offset <= limit; offset++) {
            if (startsWith(bytes, offset, PDF_HEADER)) {
                return true;
            }
        }
        return false;
    }

    private static boolean startsWith(byte[] bytes, int offset, byte[] prefix) {
        if (bytes.length < offset + prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[offset + i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static String sanitize(String value) {
        return value == null ? null : value.replace("\r", "\\r").replace("\n", "\\n");
    }

    public static final class DocumentTooLargeException extends RuntimeException {
        public DocumentTooLargeException(String message) {
            super(message);
        }
    }

    public static final class ExtractionCapacityExceededException extends RuntimeException {
        public ExtractionCapacityExceededException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class UnreadableDocumentException extends RuntimeException {
        UnreadableDocumentException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class AllPagesFailedException extends RuntimeException {
        AllPagesFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
