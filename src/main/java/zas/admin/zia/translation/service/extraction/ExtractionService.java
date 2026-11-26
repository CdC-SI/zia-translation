package zas.admin.zia.translation.service.extraction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;
import zas.admin.zia.translation.service.InvalidDocumentException;
import zas.admin.zia.translation.service.image.PixelBudget;
import zas.admin.zia.translation.service.parser.DocumentPages;
import zas.admin.zia.translation.service.parser.DocumentParser;
import zas.admin.zia.translation.service.parser.DocumentParserResolver;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Asynchronous extraction of values from a document (PDF or image) with the vision model.
 * <p>
 * When the profile classifies its pages (or the client gives page hints), the pages are first classified (main
 * document, supporting documents, other pages) and the values are extracted from the pages of the main document
 * only, sent together. Otherwise, each page is extracted on its own and the results are merged, or returned page by
 * page for a {@code perPage} profile.
 */
@Service
public class ExtractionService {

    private static final Logger log = LoggerFactory.getLogger(ExtractionService.class);

    static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

    private final ExtractionJobStore jobStore;
    private final ExtractionRequestResolver requestResolver;
    private final VisionFieldExtractor fieldExtractor;
    private final PageClassifier pageClassifier;
    private final DocumentParserResolver parserResolver;
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
        this.parserResolver = new DocumentParserResolver(parsers);
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
        DocumentParser parser = parserResolver.resolve(document);

        ExtractionJobStore.Submission submission;
        try {
            submission = jobStore.submit(idempotencyKey, created -> executor.execute(() ->
                    process(created.jobId(), document, parser, request)));
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
        DocumentPages pages = openPages(document, parser);
        try {
            return extract(pages, request);
        } finally {
            closeQuietly(pages);
        }
    }

    private ExtractionResponse extract(DocumentPages pages, ExtractionRequest request) {
        List<String> warnings = new ArrayList<>(request.warnings());
        int pageCount = pages.pageCount();
        int analysed = Math.clamp(maxPages, 0, pageCount);
        if (analysed == 0) {
            throw new UnreadableDocumentException("The document has no page.", null);
        }
        if (pageCount > analysed) {
            warnings.add("Only the first %d of %d pages were analysed".formatted(analysed, pageCount));
        }
        if (request.classifiesPages()) {
            return extractMainDocument(pages, analysed, pageCount, request, warnings);
        }

        List<PageExtraction> extractions = new ArrayList<>(analysed);
        RuntimeException lastFailure = null;
        for (int page = 1; page <= analysed; page++) {
            BufferedImage image = render(pages, page, fieldExtractor.budget());   // outside the try: an unreadable page fails the job
            try {
                extractions.add(fieldExtractor.extract(image, page, pageCount, request));
            } catch (RuntimeException ex) {
                log.warn("Extraction of page {} failed", page, ex);
                warnings.add("Page %d could not be analysed by the extraction service".formatted(page));
                lastFailure = ex;
            }
        }
        if (extractions.isEmpty()) {
            throw new AllPagesFailedException("No page of the document could be analysed.", lastFailure);
        }
        if (request.perPage()) {
            return perPage(request, extractions, warnings);
        }
        return simplifyIfRequested(request, ExtractionResultMerger.merge(request, extractions, warnings));
    }

    /**
     * Drops {@code confidence}/{@code page} of the fields and collection cells of a merged response when the
     * profile is {@code simpleOutput}: the model was only asked for the bare value, these are not meant to be
     * exposed (only {@code perPage} applied this so far, merged responses leaked them).
     */
    private static ExtractionResponse simplifyIfRequested(ExtractionRequest request, ExtractionResponse response) {
        if (!request.simpleOutput()) {
            return response;
        }
        Map<String, ExtractedField> fields = response.fields() == null ? null : simplify(response.fields());
        Map<String, List<Map<String, ExtractedField>>> collections =
                response.collections() == null ? null : simplifyRows(response.collections());
        return response.withFieldsAndCollections(fields, collections);
    }

    /** Returns the values of each page as read, without merging the pages. */
    private static ExtractionResponse perPage(ExtractionRequest request, List<PageExtraction> extractions,
                                              List<String> warnings) {
        Set<String> allWarnings = new LinkedHashSet<>(warnings);
        List<PageResult> results = new ArrayList<>(extractions.size());
        for (PageExtraction extraction : extractions) {
            Map<String, ExtractedField> fields = extraction.fields();
            Map<String, List<Map<String, ExtractedField>>> collections = extraction.collections();
            if (request.simpleOutput()) {
                fields = simplify(fields);
                collections = simplifyRows(collections);
            }
            results.add(new PageResult(extraction.page(), extraction.documentType(), fields,
                    collections.isEmpty() ? null : collections));
            extraction.warnings().forEach(warning ->
                    allWarnings.add("%s: %s".formatted(extraction.label(), warning)));
        }
        return new ExtractionResponse(request.profile(), null, null, null, List.copyOf(allWarnings), null,
                List.copyOf(results));
    }

    /**
     * Keeps only the {@code value} of each field: the confidence computed by the service (and the page) are dropped,
     * the model being asked for the bare value only (simple schema).
     */
    private static Map<String, ExtractedField> simplify(Map<String, ExtractedField> fields) {
        Map<String, ExtractedField> simplified = new LinkedHashMap<>();
        fields.forEach((key, field) -> simplified.put(key, new ExtractedField(field.value(), null, null, null)));
        return simplified;
    }

    private static Map<String, List<Map<String, ExtractedField>>> simplifyRows(
            Map<String, List<Map<String, ExtractedField>>> collections) {
        Map<String, List<Map<String, ExtractedField>>> simplified = new LinkedHashMap<>();
        collections.forEach((key, rows) ->
                simplified.put(key, rows.stream().map(ExtractionService::simplify).toList()));
        return simplified;
    }

    private static DocumentPages openPages(byte[] document, DocumentParser parser) {
        try {
            return parser.open(document);
        } catch (IOException | RuntimeException ex) {
            throw new UnreadableDocumentException("The document cannot be read.", ex);
        }
    }

    /**
     * Renders one page (from 1) on demand, at the budget of the step that sends it: pages are not kept in memory
     * between the classification and extraction.
     */
    private static BufferedImage render(DocumentPages pages, int page, PixelBudget budget) {
        try {
            return pages.render(page - 1, budget);
        } catch (IOException | RuntimeException ex) {
            throw new UnreadableDocumentException("The document cannot be read.", ex);
        }
    }

    private static void closeQuietly(DocumentPages pages) {
        try {
            pages.close();
        } catch (IOException ex) {
            log.warn("Unable to close the document", ex);
        }
    }

    /**
     * Classifies the pages (hints of the client, then the vision model for the other pages) and extracts the values
     * from the main document only, its pages being sent together.
     */
    private ExtractionResponse extractMainDocument(DocumentPages pages, int analysed, int pageCount,
                                                   ExtractionRequest request, List<String> warnings) {
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
            PageImage image = new PageImage(page, render(pages, page, pageClassifier.budget()));
            try {
                ClassifiedPage result = pageClassifier.classify(image, pageCount, request, context);
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
                    .map(page -> new PageImage(page, render(pages, page, fieldExtractor.budget())))
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
        ExtractionResponse merged = ExtractionResultMerger.merge(request, extractions, warnings)
                .withPages(resolution.pages());
        return simplifyIfRequested(request, merged);
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
