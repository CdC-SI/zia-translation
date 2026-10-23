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
import zas.admin.zia.translation.service.parser.RenderedPages;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Asynchronous extraction of values from a document (PDF or image) with the vision model.
 * <p>
 * When the profile classifies its pages (or the client gives page hints), the pages are first classified (main
 * document, supporting documents, other pages) and the values are extracted from the pages of the main document
 * only, sent together. Otherwise, each page is extracted on its own and the results are merged.
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
    private final PageClassifier pageClassifier;
    private final List<DocumentParser> parsers;
    private final Executor executor;
    private final long maxFileSizeBytes;
    private final int maxPages;
    private final int maxImagesPerCall;

    ExtractionService(
            ExtractionJobStore jobStore,
            ExtractionRequestResolver requestResolver,
            VisionFieldExtractor fieldExtractor,
            PageClassifier pageClassifier,
            List<DocumentParser> parsers,
            @Qualifier("translationTaskExecutor") Executor executor,
            @Value("${zia.translation.pdf.max-file-size}") DataSize maxFileSize,
            @Value("${zia.extraction.max-pages:20}") int maxPages,
            @Value("${zia.extraction.max-images-per-call:4}") int maxImagesPerCall) {
        if (maxImagesPerCall < 1) {
            throw new IllegalArgumentException("zia.extraction.max-images-per-call must be >= 1");
        }
        this.jobStore = jobStore;
        this.requestResolver = requestResolver;
        this.fieldExtractor = fieldExtractor;
        this.pageClassifier = pageClassifier;
        this.parsers = List.copyOf(parsers);
        this.executor = executor;
        this.maxFileSizeBytes = maxFileSize.toBytes();
        this.maxPages = maxPages;
        this.maxImagesPerCall = maxImagesPerCall;
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

        ExtractionJobStore.Submission submission;
        try {
            submission = jobStore.submit(idempotencyKey,
                    created -> executor.execute(() -> process(created.jobId(), document, parser, request)));
        } catch (RejectedExecutionException ex) {
            throw new ExtractionCapacityExceededException("Too many extractions in progress, retry later.", ex);
        }
        ExtractionJob job = submission.job();
        if (!submission.created()) {
            log.info("Extraction already submitted for key '{}': job {}", sanitize(idempotencyKey), job.jobId());
            return ExtractionJobResponse.of(job);
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
        RenderedPages rendered;
        try {
            rendered = parser.renderPages(document, maxPages);
        } catch (IOException | RuntimeException ex) {
            throw new UnreadableDocumentException("The document cannot be read.", ex);
        }
        List<byte[]> pages = rendered.pages();
        int pageCount = rendered.pageCount();
        if (pages.isEmpty()) {
            throw new UnreadableDocumentException("The document has no page.", null);
        }

        List<String> warnings = new ArrayList<>(request.warnings());
        if (pageCount > pages.size()) {
            warnings.add("Only the first %d of %d pages were analysed".formatted(pages.size(), pageCount));
        }
        if (request.classifiesPages()) {
            return extractMainDocument(pages, pageCount, request, warnings);
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

    /**
     * Classifies the pages (hints of the client, then the vision model for the other pages) and extracts the values
     * from the main document only, its pages being sent together.
     */
    private ExtractionResponse extractMainDocument(List<byte[]> images, int pageCount, ExtractionRequest request,
                                                   List<String> warnings) {
        int analysed = images.size();
        Map<Integer, PageClassification> hints = new TreeMap<>();
        for (PageClassification hint : request.pageHints()) {
            if (hint.page() <= analysed) {
                hints.put(hint.page(), hint);
            } else {
                warnings.add("Hint of page %d ignored: the page is not analysed".formatted(hint.page()));
            }
        }

        Map<Integer, PageClassification> known = new TreeMap<>(hints);
        Map<Integer, ClassifiedPage> classified = new HashMap<>();
        for (int page = 1; page <= analysed; page++) {
            if (hints.containsKey(page)) {
                continue;
            }
            int current = page;
            List<PageClassification> context = known.values().stream().filter(p -> p.page() != current).toList();
            try {
                ClassifiedPage result = pageClassifier.classify(
                        new PageImage(page, images.get(page - 1)), pageCount, request, context);
                classified.put(page, result);
                known.put(page, result.classification());
            } catch (RuntimeException ex) {
                log.warn("Classification of page {} failed", page, ex);
                warnings.add("Page %d could not be classified".formatted(page));
            }
        }

        PageClassificationResolver.Resolution resolution =
                PageClassificationResolver.resolve(analysed, hints, classified);
        warnings.addAll(resolution.warnings());
        log.info("Pages classified: {}, extracted: {}", resolution.pages().stream()
                .map(p -> p.page() + "=" + p.documentRole()).toList(), resolution.pagesToExtract());

        List<PageExtraction> extractions = new ArrayList<>();
        RuntimeException lastFailure = null;
        List<Integer> toExtract = resolution.pagesToExtract();
        for (int from = 0; from < toExtract.size(); from += maxImagesPerCall) {
            List<Integer> batch = toExtract.subList(from, Math.min(from + maxImagesPerCall, toExtract.size()));
            List<PageImage> batchImages = batch.stream()
                    .map(page -> new PageImage(page, images.get(page - 1)))
                    .toList();
            try {
                extractions.add(fieldExtractor.extract(batchImages, pageCount, request, true));
            } catch (RuntimeException ex) {
                log.warn("Extraction of pages {} failed", batch, ex);
                warnings.add("%s could not be analysed by the extraction service".formatted(describe(batch)));
                lastFailure = ex;
            }
        }
        if (extractions.isEmpty()) {
            throw new AllPagesFailedException("No page of the document could be analysed.", lastFailure);
        }
        return ExtractionResultMerger.merge(request, extractions, warnings).withPages(resolution.pages());
    }

    private static String describe(List<Integer> pages) {
        return (pages.size() == 1 ? "Page " : "Pages ")
                + String.join(", ", pages.stream().map(String::valueOf).toList());
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
