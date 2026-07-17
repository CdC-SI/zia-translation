package zas.admin.zia.translation.service;

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
import zas.admin.zia.translation.service.job.JobOutputFormat;
import zas.admin.zia.translation.service.job.JobStatus;
import zas.admin.zia.translation.service.job.TranslationJob;
import zas.admin.zia.translation.service.job.TranslationJobStore;
import zas.admin.zia.translation.service.llm.TextTranslationService;
import zas.admin.zia.translation.service.ocr.OcrExtractionService;
import zas.admin.zia.translation.service.parser.DocumentParser;
import zas.admin.zia.translation.service.parser.PageLayout;
import zas.admin.zia.translation.service.pdf.PdfGenerationService;
import zas.admin.zia.translation.service.storage.MarkdownStorageService;
import zas.admin.zia.translation.service.storage.PdfStorageService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Service
public class TranslationService {

    private static final Logger log = LoggerFactory.getLogger(TranslationService.class);
    private static final String PDF_TRANSLATION_FAILED_MESSAGE = "PDF translation failed.";
    private static final String MARKDOWN_TRANSLATION_FAILED_MESSAGE = "Markdown translation failed.";
    private static final String MARKDOWN_PAGE_SEPARATOR = "\n\n<div style=\"page-break-after: always;\"></div>\n\n";
    private static final MediaType MARKDOWN_MEDIA_TYPE = new MediaType("text", "markdown");

    private static final String PDF_MIME_TYPE = "application/pdf";
    private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F'};

    private final Map<String, DocumentParser> parsersByMimeType;
    private final OcrExtractionService ocrService;
    private final TextTranslationService textTranslationService;
    private final PdfGenerationService pdfGenerationService;
    private final TranslationJobStore translationJobStore;
    private final PdfStorageService pdfStorageService;
    private final MarkdownStorageService markdownStorageService;
    private final Executor translationTaskExecutor;
    private final Scheduler translationScheduler;
    private final TranslationStrategy defaultStrategy;
    private final long maxFileSizeBytes;

    TranslationService(
            List<DocumentParser> parsers,
            OcrExtractionService ocrService,
            TextTranslationService textTranslationService,
            PdfGenerationService pdfGenerationService,
            TranslationJobStore translationJobStore,
            PdfStorageService pdfStorageService,
            MarkdownStorageService markdownStorageService,
            @Qualifier("translationTaskExecutor") Executor translationTaskExecutor,
            @Value("${zia.translation.strategy}") String strategy,
            @Value("${zia.translation.pdf.max-file-size}") String maxFileSize) {
        this.parsersByMimeType = buildParsersByMimeType(parsers);
        this.ocrService = ocrService;
        this.textTranslationService = textTranslationService;
        this.pdfGenerationService = pdfGenerationService;
        this.translationJobStore = translationJobStore;
        this.pdfStorageService = pdfStorageService;
        this.markdownStorageService = markdownStorageService;
        this.translationTaskExecutor = translationTaskExecutor;
        this.translationScheduler = Schedulers.fromExecutor(translationTaskExecutor);
        this.defaultStrategy = TranslationStrategy.fromString(strategy);
        this.maxFileSizeBytes = parseSize(maxFileSize);
    }

    public TranslationJobResponse submitPdfTranslation(MultipartFile file, String targetLanguage, TranslationStrategy strategy) throws IOException {
        validateTargetLanguage(targetLanguage);
        byte[] bytes = validateAndRead(file);
        DocumentParser parser = resolveParser(file, bytes);

        TranslationJob job = translationJobStore.createPendingJob(JobOutputFormat.PDF);

        TranslationStrategy resolvedStrategy = resolveStrategy(strategy);
        CompletableFuture.runAsync(() -> processPdfJob(job.jobId(), parser, bytes, targetLanguage, resolvedStrategy), translationTaskExecutor);

        return toResponse(job);
    }

    public TranslationJobResponse submitMarkdownTranslation(MultipartFile file, String targetLanguage, TranslationStrategy strategy) throws IOException {
        validateTargetLanguage(targetLanguage);
        byte[] bytes = validateAndRead(file);
        DocumentParser parser = resolveParser(file, bytes);

        TranslationJob job = translationJobStore.createPendingJob(JobOutputFormat.MARKDOWN);

        TranslationStrategy resolvedStrategy = resolveStrategy(strategy);
        CompletableFuture.runAsync(() -> processMarkdownJob(job.jobId(), parser, bytes, targetLanguage, resolvedStrategy), translationTaskExecutor);

        return toResponse(job);
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

    public Flux<TranslationStreamEvent> translateToTextStream(MultipartFile file, String targetLanguage, TranslationStrategy strategy) throws IOException {
        validateTargetLanguage(targetLanguage);
        TranslationStrategy resolvedStrategy = resolveStrategy(strategy);
        return Mono.fromCallable(() -> extractPages(file))
                .subscribeOn(translationScheduler)
                .flatMapMany(pages -> Flux.range(0, pages.size())
                        .concatMap(index -> streamPageTranslation(pages.get(index), targetLanguage, index + 1, resolvedStrategy)));
    }

    private Flux<TranslationStreamEvent> streamPageTranslation(byte[] page, String targetLanguage, int pageNumber, TranslationStrategy strategy) {
        Flux<String> tokenStream;
        if (strategy == TranslationStrategy.SINGLE) {
            tokenStream = textTranslationService.translatePageSingleStrategyStream(page, targetLanguage);
        } else {
            tokenStream = Mono.fromCallable(() -> ocrService.extractText(List.of(page)).getFirst())
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
    }

    public List<String> translateToText(MultipartFile file, String targetLanguage) throws IOException {
        validateTargetLanguage(targetLanguage);
        return translatePages(extractPages(file), targetLanguage, false);
    }

    public byte[] translateToPdf(MultipartFile file, String targetLanguage) throws IOException {
        validateTargetLanguage(targetLanguage);
        byte[] bytes = validateAndRead(file);
        DocumentParser parser = resolveParser(file, bytes);
        List<PageLayout> pageLayouts;
        List<byte[]> pages;
        try {
            pageLayouts = parser.extractPageLayouts(bytes);
            pages = parser.renderPages(bytes);
        } catch (IOException exception) {
            throw new InvalidDocumentException("Document is invalid or cannot be parsed.", exception);
        }
        List<String> translatedPages = translatePages(pages, targetLanguage, true);
        return pdfGenerationService.generatePdf(translatedPages, pageLayouts);
    }

    private void processPdfJob(String jobId, DocumentParser parser, byte[] bytes, String targetLanguage, TranslationStrategy strategy) {
        translationJobStore.markProcessing(jobId);
        try {
            List<PageLayout> pageLayouts = parser.extractPageLayouts(bytes);
            List<byte[]> pages = parser.renderPages(bytes);
            List<String> translatedPages = translatePages(pages, targetLanguage, true, strategy);
            byte[] generatedPdf = pdfGenerationService.generatePdf(translatedPages, pageLayouts);
            pdfStorageService.store(jobId, generatedPdf);
            translationJobStore.markCompleted(jobId);
        } catch (Exception exception) {
            log.error("PDF translation job failed for jobId={}", jobId, exception);
            translationJobStore.markFailed(jobId, PDF_TRANSLATION_FAILED_MESSAGE);
        }
    }

    private void processMarkdownJob(String jobId, DocumentParser parser, byte[] bytes, String targetLanguage, TranslationStrategy strategy) {
        translationJobStore.markProcessing(jobId);
        try {
            List<byte[]> pages = parser.renderPages(bytes);
            List<String> translatedPages = translatePages(pages, targetLanguage, true, strategy);
            String mergedMarkdown = mergeMarkdownPages(translatedPages);
            markdownStorageService.store(jobId, mergedMarkdown.getBytes(StandardCharsets.UTF_8));
            translationJobStore.markCompleted(jobId);
        } catch (Exception exception) {
            log.error("Markdown translation job failed for jobId={}", jobId, exception);
            translationJobStore.markFailed(jobId, MARKDOWN_TRANSLATION_FAILED_MESSAGE);
        }
    }

    static String mergeMarkdownPages(List<String> pages) {
        return String.join(MARKDOWN_PAGE_SEPARATOR, pages);
    }

    private List<String> translatePages(List<byte[]> pages, String targetLanguage, boolean renderAsMarkdown) {
        return translatePages(pages, targetLanguage, renderAsMarkdown, defaultStrategy);
    }

    private List<String> translatePages(List<byte[]> pages, String targetLanguage, boolean renderAsMarkdown, TranslationStrategy strategy) {
        try {
            if (strategy == TranslationStrategy.SINGLE) {
                return textTranslationService.translatePagesSingleStrategy(pages, targetLanguage, renderAsMarkdown);
            }
            List<String> extracted = ocrService.extractText(pages);
            return textTranslationService.translatePages(extracted, targetLanguage, renderAsMarkdown);
        } catch (TranslationProcessingException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new TranslationProcessingException("Failed to process document translation.", ex);
        }
    }

    private String translatePage(byte[] page, String targetLanguage, boolean renderAsMarkdown) {
        return translatePages(List.of(page), targetLanguage, renderAsMarkdown).getFirst();
    }

    private TranslationStrategy resolveStrategy(TranslationStrategy requestStrategy) {
        return requestStrategy != null ? requestStrategy : this.defaultStrategy;
    }

    private List<byte[]> extractPages(MultipartFile file) throws IOException {
        byte[] bytes = validateAndRead(file);
        DocumentParser parser = resolveParser(file, bytes);
        try {
            return parser.renderPages(bytes);
        } catch (IOException exception) {
            throw new InvalidDocumentException("Document is invalid or cannot be parsed.", exception);
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

    private void validateTargetLanguage(String targetLanguage) {
        if (targetLanguage == null || targetLanguage.isBlank()) {
            throw new InvalidDocumentException("Target language is missing or empty.");
        }
    }

    private DocumentParser resolveParser(MultipartFile file, byte[] bytes) {
        String contentType = file.getContentType();
        DocumentParser parser = null;
        if (contentType != null) {
            parser = parsersByMimeType.get(contentType);
        }
        if (parser == null) {
            if (isPdf(bytes)) {
                parser = parsersByMimeType.get(PDF_MIME_TYPE);
            }
        }
        if (parser == null) {
            throw new InvalidDocumentException(
                    "Unsupported file format: '%s'. Supported formats: %s."
                            .formatted(contentType, parsersByMimeType.keySet()));
        }
        return parser;
    }

    private static boolean isPdf(byte[] bytes) {
        if (bytes.length < PDF_MAGIC.length) {
            return false;
        }
        for (int i = 0; i < PDF_MAGIC.length; i++) {
            if (bytes[i] != PDF_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    private TranslationJobResponse toResponse(TranslationJob job) {
        return new TranslationJobResponse(job.jobId(), job.status());
    }

    private static Map<String, DocumentParser> buildParsersByMimeType(List<DocumentParser> parsers) {
        Map<String, DocumentParser> parserMap = new LinkedHashMap<>();
        for (DocumentParser parser : parsers) {
            for (String mimeType : parser.supportedMimeTypes()) {
                DocumentParser previous = parserMap.putIfAbsent(mimeType, parser);
                if (previous != null && previous != parser) {
                    throw new IllegalStateException("Duplicate parser mapping for MIME type '%s'."
                            .formatted(mimeType));
                }
            }
        }
        return Map.copyOf(parserMap);
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
