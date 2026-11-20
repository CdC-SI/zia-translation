package zas.admin.zia.translation.service;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import zas.admin.zia.translation.service.dto.TranslationJobResponse;
import zas.admin.zia.translation.service.dto.TranslationStreamEvent;
import zas.admin.zia.translation.service.image.ImagePreprocessingException;
import zas.admin.zia.translation.service.job.JobOutputFormat;
import zas.admin.zia.translation.service.job.JobStatus;
import zas.admin.zia.translation.service.job.TranslationJob;
import zas.admin.zia.translation.service.job.TranslationJobStore;
import zas.admin.zia.translation.service.language.TargetLanguage;
import zas.admin.zia.translation.service.language.TargetLanguageResolver;
import zas.admin.zia.translation.service.llm.TextTranslationService;
import zas.admin.zia.translation.service.ocr.OcrExtractionService;
import zas.admin.zia.translation.service.parser.DocumentPages;
import zas.admin.zia.translation.service.parser.DocumentParser;
import zas.admin.zia.translation.service.parser.DocumentParserResolver;
import zas.admin.zia.translation.service.parser.PageLayout;
import zas.admin.zia.translation.service.pdf.PdfGenerationService;
import zas.admin.zia.translation.service.storage.MarkdownStorageService;
import zas.admin.zia.translation.service.storage.PdfStorageService;

@Service
public class TranslationService {

    private static final Logger log = LoggerFactory.getLogger(TranslationService.class);
    private static final String PDF_TRANSLATION_FAILED_MESSAGE = "PDF translation failed.";
    private static final String MARKDOWN_TRANSLATION_FAILED_MESSAGE = "Markdown translation failed.";
    private static final String MARKDOWN_PAGE_SEPARATOR = "\n\n<div style=\"page-break-after: always;\"></div>\n\n";
    private static final MediaType MARKDOWN_MEDIA_TYPE = new MediaType("text", "markdown");

    private final DocumentParserResolver parserResolver;
    private final OcrExtractionService ocrService;
    private final TextTranslationService textTranslationService;
    private final PdfGenerationService pdfGenerationService;
    private final TranslationJobStore translationJobStore;
    private final PdfStorageService pdfStorageService;
    private final MarkdownStorageService markdownStorageService;
    private final TargetLanguageResolver targetLanguageResolver;
    private final Executor translationTaskExecutor;
    private final Scheduler translationScheduler;
    private final TranslationStrategy defaultStrategy;
    private final long maxFileSizeBytes;
    private final int maxTextLength;

    TranslationService(
            List<DocumentParser> parsers,
            OcrExtractionService ocrService,
            TextTranslationService textTranslationService,
            PdfGenerationService pdfGenerationService,
            TranslationJobStore translationJobStore,
            PdfStorageService pdfStorageService,
            MarkdownStorageService markdownStorageService,
            TargetLanguageResolver targetLanguageResolver,
            @Qualifier("translationTaskExecutor") Executor translationTaskExecutor,
            @Value("${zia.translation.strategy}") String strategy,
            @Value("${zia.translation.pdf.max-file-size}") String maxFileSize,
            @Value("${zia.translation.text.max-length}") int maxTextLength) {

        this.parserResolver = new DocumentParserResolver(parsers);
        this.ocrService = ocrService;
        this.textTranslationService = textTranslationService;
        this.pdfGenerationService = pdfGenerationService;
        this.translationJobStore = translationJobStore;
        this.pdfStorageService = pdfStorageService;
        this.markdownStorageService = markdownStorageService;
        this.targetLanguageResolver = targetLanguageResolver;
        this.translationTaskExecutor = translationTaskExecutor;
        this.translationScheduler = Schedulers.fromExecutor(translationTaskExecutor);
        this.defaultStrategy = TranslationStrategy.fromString(strategy);
        this.maxFileSizeBytes = parseSize(maxFileSize);
        this.maxTextLength = maxTextLength;
    }

    public TranslationJobResponse submitPdfTranslation(MultipartFile file, String targetLanguage, TranslationStrategy strategy) throws IOException {
        TargetLanguage language = targetLanguageResolver.resolve(targetLanguage);
        byte[] bytes = validateAndRead(file);
        DocumentParser parser = parserResolver.resolve(bytes);

        TranslationStrategy resolvedStrategy = resolveStrategy(strategy);
        TranslationJob job = submitJob(JobOutputFormat.PDF,
                jobId -> processPdfJob(jobId, parser, bytes, language, resolvedStrategy));

        return toResponse(job);
    }

    public TranslationJobResponse submitMarkdownTranslation(MultipartFile file, String targetLanguage, TranslationStrategy strategy) throws IOException {
        TargetLanguage language = targetLanguageResolver.resolve(targetLanguage);
        byte[] bytes = validateAndRead(file);
        DocumentParser parser = parserResolver.resolve(bytes);

        TranslationStrategy resolvedStrategy = resolveStrategy(strategy);
        TranslationJob job = submitJob(JobOutputFormat.MARKDOWN,
                jobId -> processMarkdownJob(jobId, parser, bytes, language, resolvedStrategy));

        return toResponse(job);
    }

    /**
     * Creates the job and queues its processing as one step: when the executor is full, the job is removed again
     * and the caller gets a {@link TranslationCapacityExceededException} (HTTP 429) instead of a job stuck in PENDING.
     */
    private TranslationJob submitJob(JobOutputFormat outputFormat, Consumer<String> processing) {
        TranslationJob job = translationJobStore.createPendingJob(outputFormat);
        try {
            translationTaskExecutor.execute(() -> processing.accept(job.jobId()));
        } catch (RejectedExecutionException exception) {
            translationJobStore.delete(job.jobId());
            log.warn("Translation queue full, rejected {} job", outputFormat);
            throw new TranslationCapacityExceededException("Too many translations in progress, retry later.", exception);
        }
        return job;
    }

    public Optional<TranslationJobResponse> getJobStatusResponse(String jobId) {
        return translationJobStore.findById(jobId).map(this::toResponse);
    }

    public Optional<JobStatus> getJobStatus(String jobId) {
        return translationJobStore.findById(jobId).map(TranslationJob::status);
    }

    public Optional<TranslatedFile> getTranslatedFile(String jobId) {
        return translationJobStore.findById(jobId).flatMap(job -> {
            if (job.outputFormat() == JobOutputFormat.MARKDOWN) {
                return markdownStorageService.load(jobId)
                        .map(resource -> new TranslatedFile(resource, MARKDOWN_MEDIA_TYPE, jobId + ".md"));
            }
            return pdfStorageService.load(jobId)
                    .map(resource -> new TranslatedFile(resource, MediaType.APPLICATION_PDF, jobId + ".pdf"));
        });
    }

    public Flux<TranslationStreamEvent> translateToTextStream(MultipartFile file, String targetLanguage, TranslationStrategy strategy) {
        TargetLanguage language = targetLanguageResolver.resolve(targetLanguage);
        TranslationStrategy resolvedStrategy = resolveStrategy(strategy);
        // the document is opened once and closed when the stream completes, fails or is cancelled
        return Flux.usingWhen(
                Mono.fromCallable(() -> openPages(file)).subscribeOn(translationScheduler),
                pages -> Flux.range(0, pages.pageCount())
                        .concatMap(index -> streamPageTranslation(pages, index, language, resolvedStrategy)),
                pages -> Mono.fromRunnable(() -> closeQuietly(pages)));
    }

    private Flux<TranslationStreamEvent> streamPageTranslation(DocumentPages pages, int index, TargetLanguage targetLanguage, TranslationStrategy strategy) {
        int pageNumber = index + 1;
        return Flux.defer(() -> {
            Flux<String> tokenStream;
            if (strategy == TranslationStrategy.SINGLE) {
                tokenStream = Mono.fromCallable(() ->
                                textTranslationService.translatePageSingleStrategyStream(renderPage(pages, index), targetLanguage))
                        .subscribeOn(translationScheduler)
                        .flatMapMany(stream -> stream);
            } else {
                tokenStream = Mono.fromCallable(() -> ocrService.extractText(renderPage(pages, index)))
                        .subscribeOn(translationScheduler)
                        .flatMapMany(extractedText -> textTranslationService.translatePageStream(extractedText, targetLanguage));
            }

            StringBuilder accumulated = new StringBuilder();
            return tokenStream
                    .map(token -> {
                        accumulated.append(token);
                        return (TranslationStreamEvent) new TranslationStreamEvent.Token(pageNumber, token);
                    })
                    .concatWith(Mono.fromSupplier(() -> new TranslationStreamEvent.PageComplete(pageNumber, accumulated.toString())));
        }).onErrorMap(ImagePreprocessingException.class,
                ex -> new TranslationProcessingException("Failed to process document translation.", ex));
    }

    public List<String> translateToText(MultipartFile file, String targetLanguage) throws IOException {
        TargetLanguage language = targetLanguageResolver.resolve(targetLanguage);
        try (DocumentPages pages = openPages(file)) {
            return translatePages(pages, language, false, defaultStrategy);
        }
    }

    public String translatePlainText(String text, String targetLanguage) {
        validateText(text);
        TargetLanguage language = targetLanguageResolver.resolve(targetLanguage);
        try {
            return textTranslationService.translateText(text, language).strip();
        } catch (RuntimeException ex) {
            throw new TranslationProcessingException("Failed to process text translation.", ex);
        }
    }

    public byte[] translateToPdf(MultipartFile file, String targetLanguage) throws IOException {
        TargetLanguage language = targetLanguageResolver.resolve(targetLanguage);
        try (DocumentPages pages = openPages(file)) {
            List<PageLayout> pageLayouts = layouts(pages);
            List<String> translatedPages = translatePages(pages, language, true, defaultStrategy);
            return pdfGenerationService.generatePdf(translatedPages, pageLayouts, language);
        }
    }

    private void processPdfJob(String jobId, DocumentParser parser, byte[] bytes, TargetLanguage targetLanguage, TranslationStrategy strategy) {
        translationJobStore.markProcessing(jobId);
        try (DocumentPages pages = parser.open(bytes)) {
            List<PageLayout> pageLayouts = layouts(pages);
            List<String> translatedPages = translatePages(pages, targetLanguage, true, strategy);
            byte[] generatedPdf = pdfGenerationService.generatePdf(translatedPages, pageLayouts, targetLanguage);
            pdfStorageService.store(jobId, generatedPdf);
            translationJobStore.markCompleted(jobId);
        } catch (Exception exception) {
            log.error("PDF translation job failed for jobId={}", jobId, exception);
            translationJobStore.markFailed(jobId, PDF_TRANSLATION_FAILED_MESSAGE);
        } catch (Error error) {
            // e.g. OutOfMemoryError: the job must still end, otherwise it stays PROCESSING forever
            log.error("PDF translation job failed for jobId={}", jobId, error);
            translationJobStore.markFailed(jobId, PDF_TRANSLATION_FAILED_MESSAGE);
            throw error;
        }
    }

    private void processMarkdownJob(String jobId, DocumentParser parser, byte[] bytes, TargetLanguage targetLanguage, TranslationStrategy strategy) {
        translationJobStore.markProcessing(jobId);
        try (DocumentPages pages = parser.open(bytes)) {
            List<String> translatedPages = translatePages(pages, targetLanguage, true, strategy);
            String mergedMarkdown = mergeMarkdownPages(translatedPages);
            markdownStorageService.store(jobId, mergedMarkdown.getBytes(StandardCharsets.UTF_8));
            translationJobStore.markCompleted(jobId);
        } catch (Exception exception) {
            log.error("Markdown translation job failed for jobId={}", jobId, exception);
            translationJobStore.markFailed(jobId, MARKDOWN_TRANSLATION_FAILED_MESSAGE);
        } catch (Error error) {
            // e.g. OutOfMemoryError: the job must still end, otherwise it stays PROCESSING forever
            log.error("Markdown translation job failed for jobId={}", jobId, error);
            translationJobStore.markFailed(jobId, MARKDOWN_TRANSLATION_FAILED_MESSAGE);
            throw error;
        }
    }

    static String mergeMarkdownPages(List<String> pages) {
        return String.join(MARKDOWN_PAGE_SEPARATOR, pages);
    }

    /**
     * Translates the pages one after another; each page is rendered just before its model call and dropped after it,
     * so at most one page image is in memory.
     */
    private List<String> translatePages(DocumentPages pages, TargetLanguage targetLanguage, boolean renderAsMarkdown, TranslationStrategy strategy) {
        List<String> translated = new ArrayList<>(pages.pageCount());
        for (int index = 0; index < pages.pageCount(); index++) {
            BufferedImage page = renderPage(pages, index);
            translated.add(translatePage(page, targetLanguage, renderAsMarkdown, strategy));
        }
        return translated;
    }

    private String translatePage(BufferedImage page, TargetLanguage targetLanguage, boolean renderAsMarkdown, TranslationStrategy strategy) {
        try {
            if (strategy == TranslationStrategy.SINGLE) {
                return textTranslationService.translatePageSingleStrategy(page, targetLanguage, renderAsMarkdown);
            }
            String extracted = ocrService.extractText(page);
            return textTranslationService.translatePage(extracted, targetLanguage, renderAsMarkdown);
        } catch (TranslationProcessingException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new TranslationProcessingException("Failed to process document translation.", ex);
        }
    }

    private static List<PageLayout> layouts(DocumentPages pages) {
        List<PageLayout> layouts = new ArrayList<>(pages.pageCount());
        for (int index = 0; index < pages.pageCount(); index++) {
            layouts.add(pages.layout(index));
        }
        return layouts;
    }

    private static BufferedImage renderPage(DocumentPages pages, int index) {
        try {
            return pages.render(index);
        } catch (IOException exception) {
            throw new InvalidDocumentException("Document is invalid or cannot be parsed.", exception);
        }
    }

    private TranslationStrategy resolveStrategy(TranslationStrategy requestStrategy) {
        return requestStrategy != null ? requestStrategy : this.defaultStrategy;
    }

    private DocumentPages openPages(MultipartFile file) throws IOException {
        byte[] bytes = validateAndRead(file);
        DocumentParser parser = parserResolver.resolve(bytes);
        try {
            return parser.open(bytes);
        } catch (IOException exception) {
            throw new InvalidDocumentException("Document is invalid or cannot be parsed.", exception);
        }
    }

    private static void closeQuietly(DocumentPages pages) {
        try {
            pages.close();
        } catch (IOException exception) {
            log.warn("Unable to close the document", exception);
        }
    }

    private byte[] validateAndRead(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new InvalidDocumentException("File is missing or empty.");
        }
        byte[] bytes = file.getBytes();
        if (bytes.length > maxFileSizeBytes) {
            throw new InvalidDocumentException(
                    "File size (%d bytes) exceeds maximum allowed (%d bytes).".formatted(bytes.length, maxFileSizeBytes));
        }
        return bytes;
    }

    private void validateText(String text) {
        if (text == null || text.isBlank()) {
            throw new InvalidDocumentException("Text is missing or empty.");
        }
        if (text.length() > maxTextLength) {
            throw new InvalidDocumentException(
                    "Text length (%d characters) exceeds maximum allowed (%d characters)."
                            .formatted(text.length(), maxTextLength));
        }
    }

    private TranslationJobResponse toResponse(TranslationJob job) {
        return new TranslationJobResponse(job.jobId(), job.status());
    }

    private static long parseSize(String sizeStr) {
        if (sizeStr == null || sizeStr.isBlank()) {
            return 10L * 1024 * 1024;
        }
        String trimmed = sizeStr.trim().toUpperCase();
        if (trimmed.endsWith("MB")) {
            return Long.parseLong(trimmed.replace("MB", "").trim()) * 1024L * 1024;
        }
        if (trimmed.endsWith("KB")) {
            return Long.parseLong(trimmed.replace("KB", "").trim()) * 1024L;
        }
        return Long.parseLong(trimmed);
    }

    /**
     * A translated file ready to be downloaded, along with the metadata needed
     * to build the HTTP response (Content-Type and file name).
     */
    public record TranslatedFile(Resource resource, MediaType mediaType, String filename) {}
}
