package zas.admin.zia.translation.service.invoice;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Asynchronous extraction of the invoice values of a scanned medical invoice, page by page with the vision model.
 */
@Service
class InvoiceExtractionService {

    private static final Logger log = LoggerFactory.getLogger(InvoiceExtractionService.class);

    static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;
    private static final String PDF_MIME_TYPE = "application/pdf";
    private static final byte[] PDF_HEADER = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    private static final int PDF_HEADER_SEARCH_LIMIT = 1024;

    private final InvoiceExtractionJobStore jobStore;
    private final VisionInvoiceFieldExtractor fieldExtractor;
    private final DocumentParser pdfParser;
    private final Executor executor;
    private final long maxFileSizeBytes;
    private final int maxPages;

    InvoiceExtractionService(
            InvoiceExtractionJobStore jobStore,
            VisionInvoiceFieldExtractor fieldExtractor,
            List<DocumentParser> parsers,
            @Qualifier("translationTaskExecutor") Executor executor,
            @Value("${zia.translation.pdf.max-file-size}") DataSize maxFileSize,
            @Value("${zia.invoice-extraction.max-pages:20}") int maxPages) {
        this.jobStore = jobStore;
        this.fieldExtractor = fieldExtractor;
        this.pdfParser = parsers.stream()
                .filter(parser -> parser.supportedMimeTypes().contains(PDF_MIME_TYPE))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No PDF parser available"));
        this.executor = executor;
        this.maxFileSizeBytes = maxFileSize.toBytes();
        this.maxPages = maxPages;
    }

    /**
     * Creates the job of the submission (or returns the existing job of the {@code Idempotency-Key}).
     *
     * @throws InvalidDocumentException                   invalid key, document or context
     * @throws DocumentTooLargeException                  document larger than the configured maximum
     * @throws ExtractionCapacityExceededException        no capacity left to process the job
     */
    ExtractionJobResponse submit(String idempotencyKey, MultipartFile file, ExtractionContext context)
            throws IOException {
        validateIdempotencyKey(idempotencyKey);
        if (context == null) {
            throw new InvalidDocumentException("Part 'context' is missing.");
        }
        byte[] pdf = readPdf(file);

        InvoiceExtractionJobStore.Submission submission = jobStore.submit(idempotencyKey);
        InvoiceExtractionJob job = submission.job();
        if (!submission.created()) {
            log.info("Invoice extraction already submitted for key '{}': job {}", sanitize(idempotencyKey), job.jobId());
            return ExtractionJobResponse.of(job);
        }
        try {
            executor.execute(() -> process(job.jobId(), pdf, context));
        } catch (RejectedExecutionException ex) {
            jobStore.delete(job.jobId());
            throw new ExtractionCapacityExceededException("Too many extractions in progress, retry later.", ex);
        }
        log.info("Invoice extraction job {} submitted for document '{}' ({} missing fields)",
                job.jobId(), sanitize(context.documentId()), context.missingFields().size());
        return ExtractionJobResponse.of(job);
    }

    Optional<ExtractionJobResponse> find(String jobId) {
        return jobStore.findById(jobId).map(ExtractionJobResponse::of);
    }

    void process(String jobId, byte[] pdf, ExtractionContext context) {
        jobStore.markRunning(jobId);
        try {
            jobStore.markSucceeded(jobId, extract(pdf, context));
            log.info("Invoice extraction job {} succeeded", jobId);
        } catch (UnreadableDocumentException ex) {
            log.warn("Invoice extraction job {} failed: unreadable document", jobId, ex);
            jobStore.markFailed(jobId, new ExtractionError(ExtractionError.UNREADABLE_DOCUMENT, ex.getMessage()));
        } catch (AllPagesFailedException ex) {
            log.error("Invoice extraction job {} failed: no page could be extracted", jobId, ex);
            jobStore.markFailed(jobId, new ExtractionError(ExtractionError.EXTRACTION_FAILED, ex.getMessage()));
        } catch (RuntimeException ex) {
            log.error("Invoice extraction job {} failed", jobId, ex);
            jobStore.markFailed(jobId, new ExtractionError(ExtractionError.INTERNAL_ERROR, "Unexpected extraction error."));
        }
    }

    private ExtractionResponse extract(byte[] pdf, ExtractionContext context) {
        List<byte[]> pages;
        try {
            pages = pdfParser.renderPages(pdf);
        } catch (IOException | RuntimeException ex) {
            throw new UnreadableDocumentException("The PDF document cannot be read.", ex);
        }
        if (pages.isEmpty()) {
            throw new UnreadableDocumentException("The PDF document has no page.", null);
        }

        Set<String> requestedFields = requestedFields(context);
        boolean servicesRequested = context.missingFields().isEmpty()
                || context.missingFields().contains(InvoiceFieldKeys.SERVICES);
        List<String> warnings = new ArrayList<>();
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
                extractions.add(fieldExtractor.extract(pages.get(index), page, pageCount, context,
                        requestedFields, servicesRequested));
            } catch (RuntimeException ex) {
                log.warn("Extraction of page {} failed", page, ex);
                warnings.add("Page %d could not be analysed by the OCR service".formatted(page));
                lastFailure = ex;
            }
        }
        if (extractions.isEmpty()) {
            throw new AllPagesFailedException("No page of the document could be analysed.", lastFailure);
        }
        return ExtractionResultMerger.merge(extractions, requestedFields, servicesRequested, warnings);
    }

    /** The known {@code missingFields} or, when none is given, every value key. */
    static Set<String> requestedFields(ExtractionContext context) {
        if (context.missingFields().isEmpty()) {
            return InvoiceFieldKeys.VALUE_KEYS;
        }
        Set<String> requested = new LinkedHashSet<>();
        for (String key : context.missingFields()) {
            if (InvoiceFieldKeys.VALUE_KEYS.contains(key)) {
                requested.add(key);
            } else if (!InvoiceFieldKeys.SERVICES.equals(key)) {
                log.debug("Unknown missing field '{}' ignored", sanitize(key));
            }
        }
        return requested;
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

    private byte[] readPdf(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new InvalidDocumentException("Part 'file' is missing or empty.");
        }
        if (file.getSize() > maxFileSizeBytes) {
            throw new DocumentTooLargeException(
                    "File size (%d bytes) exceeds maximum allowed (%d bytes).".formatted(file.getSize(), maxFileSizeBytes));
        }
        byte[] bytes = file.getBytes();
        if (!hasPdfHeader(bytes)) {
            throw new InvalidDocumentException("The document is not a PDF.");
        }
        return bytes;
    }

    static boolean hasPdfHeader(byte[] bytes) {
        int limit = Math.min(bytes.length, PDF_HEADER_SEARCH_LIMIT) - PDF_HEADER.length;
        for (int offset = 0; offset <= limit; offset++) {
            if (startsWith(bytes, offset)) {
                return true;
            }
        }
        return false;
    }

    private static boolean startsWith(byte[] bytes, int offset) {
        for (int i = 0; i < PDF_HEADER.length; i++) {
            if (bytes[offset + i] != PDF_HEADER[i]) {
                return false;
            }
        }
        return true;
    }

    private static String sanitize(String value) {
        return value == null ? null : value.replace("\r", "\\r").replace("\n", "\\n");
    }

    static final class DocumentTooLargeException extends RuntimeException {
        DocumentTooLargeException(String message) {
            super(message);
        }
    }

    static final class ExtractionCapacityExceededException extends RuntimeException {
        ExtractionCapacityExceededException(String message, Throwable cause) {
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
