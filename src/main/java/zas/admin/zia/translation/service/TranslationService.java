package zas.admin.zia.translation.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import zas.admin.zia.translation.service.dto.TranslationJobResponse;
import zas.admin.zia.translation.service.dto.TranslationPageEvent;
import zas.admin.zia.translation.service.job.JobStatus;
import zas.admin.zia.translation.service.job.TranslationJob;
import zas.admin.zia.translation.service.job.TranslationJobStore;
import zas.admin.zia.translation.service.llm.TextTranslationService;
import zas.admin.zia.translation.service.ocr.OcrExtractionService;
import zas.admin.zia.translation.service.parser.DocumentParser;
import zas.admin.zia.translation.service.parser.PageLayout;
import zas.admin.zia.translation.service.pdf.PdfGenerationService;
import zas.admin.zia.translation.service.storage.PdfStorageService;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class TranslationService {

    private static final Logger log = LoggerFactory.getLogger(TranslationService.class);
    static final String STRATEGY_SINGLE = "single";
    static final String STRATEGY_DUAL = "dual";
    private static final String PDF_TRANSLATION_FAILED_MESSAGE = "PDF translation failed.";

    private static final String PDF_MIME_TYPE = "application/pdf";
    private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F'};

    private final Map<String, DocumentParser> parsersByMimeType;
    private final OcrExtractionService ocrService;
    private final TextTranslationService textTranslationService;
    private final PdfGenerationService pdfGenerationService;
    private final TranslationJobStore translationJobStore;
    private final PdfStorageService pdfStorageService;
    private final Executor translationTaskExecutor;
    private final Scheduler translationScheduler;
    private final String strategy;
    private final long maxFileSizeBytes;

    TranslationService(
            List<DocumentParser> parsers,
            OcrExtractionService ocrService,
            TextTranslationService textTranslationService,
            PdfGenerationService pdfGenerationService,
            TranslationJobStore translationJobStore,
            PdfStorageService pdfStorageService,
            @Qualifier("translationTaskExecutor") Executor translationTaskExecutor,
            @Value("${zia.translation.strategy}") String strategy,
            @Value("${zia.translation.pdf.max-file-size}") String maxFileSize) {
        this.parsersByMimeType = parsers.stream()
                .collect(Collectors.toMap(DocumentParser::supportedMimeType, Function.identity()));
        this.ocrService = ocrService;
        this.textTranslationService = textTranslationService;
        this.pdfGenerationService = pdfGenerationService;
        this.translationJobStore = translationJobStore;
        this.pdfStorageService = pdfStorageService;
        this.translationTaskExecutor = translationTaskExecutor;
        this.translationScheduler = Schedulers.fromExecutor(translationTaskExecutor);
        this.strategy = strategy;
        this.maxFileSizeBytes = parseSize(maxFileSize);
    }

    public TranslationJobResponse submitPdfTranslation(MultipartFile file, String targetLanguage) throws IOException {
        validateTargetLanguage(targetLanguage);
        byte[] bytes = validateAndRead(file);
        DocumentParser parser = resolveParser(file, bytes);

        TranslationJob job = translationJobStore.createPendingJob();

        CompletableFuture.runAsync(() -> processPdfJob(job.jobId(), parser, bytes, targetLanguage), translationTaskExecutor);

        return toResponse(job);
    }

    public Optional<TranslationJobResponse> getJobStatusResponse(String jobId) {
        return translationJobStore.findById(jobId).map(this::toResponse);
    }

    public Optional<JobStatus> getJobStatus(String jobId) {
        return translationJobStore.findById(jobId).map(TranslationJob::status);
    }

    public Optional<Resource> getTranslatedPdf(String jobId) {
        return pdfStorageService.load(jobId);
    }

    public Flux<TranslationPageEvent> translateToTextStream(MultipartFile file, String targetLanguage) throws IOException {
        validateTargetLanguage(targetLanguage);
        return Mono.fromCallable(() -> extractPages(file))
                .subscribeOn(translationScheduler)
                .flatMapMany(pages -> Flux.range(0, pages.size())
                        .concatMap(index -> Mono.fromCallable(() -> translatePage(pages.get(index), targetLanguage, false))
                                .subscribeOn(translationScheduler)
                                .map(translatedText -> new TranslationPageEvent(index + 1, translatedText))));
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

    private void processPdfJob(String jobId, DocumentParser parser, byte[] bytes, String targetLanguage) {
        translationJobStore.markProcessing(jobId);
        try {
            List<PageLayout> pageLayouts = parser.extractPageLayouts(bytes);
            List<byte[]> pages = parser.renderPages(bytes);
            List<String> translatedPages = translatePages(pages, targetLanguage, true);
            byte[] generatedPdf = pdfGenerationService.generatePdf(translatedPages, pageLayouts);
            pdfStorageService.store(jobId, generatedPdf);
            translationJobStore.markCompleted(jobId);
        } catch (Exception exception) {
            log.error("PDF translation job failed for jobId={}", jobId, exception);
            translationJobStore.markFailed(jobId, PDF_TRANSLATION_FAILED_MESSAGE);
        }
    }

    private List<String> translatePages(List<byte[]> pages, String targetLanguage, boolean renderAsMarkdown) {
        try {
            if (STRATEGY_SINGLE.equals(strategy)) {
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
                    "Unsupported file format: '%s'. Only PDF is currently supported.".formatted(contentType));
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
}
