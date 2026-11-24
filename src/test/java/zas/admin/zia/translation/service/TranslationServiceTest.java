package zas.admin.zia.translation.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import zas.admin.zia.translation.service.dto.TranslationStreamEvent;
import zas.admin.zia.translation.service.image.ImagePreprocessingException;
import zas.admin.zia.translation.service.llm.TextTranslationService;
import zas.admin.zia.translation.service.ocr.OcrExtractionService;
import zas.admin.zia.translation.service.job.JobOutputFormat;
import zas.admin.zia.translation.service.job.JobStatus;
import zas.admin.zia.translation.service.job.TranslationJob;
import zas.admin.zia.translation.service.job.TranslationJobStore;
import zas.admin.zia.translation.service.language.TargetLanguage;
import zas.admin.zia.translation.service.language.TargetLanguageResolver;
import zas.admin.zia.translation.service.parser.DocumentParser;
import zas.admin.zia.translation.service.parser.PageLayout;
import zas.admin.zia.translation.service.parser.TestDocumentPages;
import zas.admin.zia.translation.service.pdf.PdfGenerationService;
import zas.admin.zia.translation.service.storage.MarkdownStorageService;
import zas.admin.zia.translation.service.storage.PdfStorageService;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TranslationServiceTest {

    private static final byte[] PDF_BYTES = {'%', 'P', 'D', 'F', '-', '1', '.', '4'};

    @Mock
    private DocumentParser pdfParser;
    @Mock
    private DocumentParser imageParser;

    @Mock
    private OcrExtractionService ocrService;

    @Mock
    private TextTranslationService textTranslationService;

    @Mock
    private PdfGenerationService pdfGenerationService;
    @Mock
    private TranslationJobStore translationJobStore;
    @Mock
    private PdfStorageService pdfStorageService;
    @Mock
    private MarkdownStorageService markdownStorageService;

    private TranslationService dualService;
    private TranslationService singleService;

    @BeforeEach
    void setUp() throws IOException {
        when(pdfParser.supportedMimeTypes()).thenReturn(List.of("application/pdf"));
        when(imageParser.supportedMimeTypes()).thenReturn(List.of(
                "image/png",
                "image/jpeg",
                "image/jpg",
                "image/gif",
                "image/bmp"));

        dualService = new TranslationService(
                List.of(pdfParser, imageParser), ocrService, textTranslationService, pdfGenerationService,
                translationJobStore, pdfStorageService, markdownStorageService, new TargetLanguageResolver("fr"), Runnable::run,
                "dual", "10MB", 20000, 1);

        singleService = new TranslationService(
                List.of(pdfParser, imageParser), ocrService, textTranslationService, pdfGenerationService,
                translationJobStore, pdfStorageService, markdownStorageService, new TargetLanguageResolver("fr"), Runnable::run,
                "single", "10MB", 20000, 1);
    }

    // --- validation ---

    @Test
    void translateToText_nullFile_throwsInvalidDocumentException() {
        assertThatThrownBy(() -> dualService.translateToText(null, "fr"))
                .isInstanceOf(InvalidDocumentException.class)
                .hasMessageContaining("missing or empty");
    }

    @Test
    void translateToText_emptyFile_throwsInvalidDocumentException() {
        MockMultipartFile file = new MockMultipartFile("file", "empty.pdf", "application/pdf", new byte[0]);

        assertThatThrownBy(() -> dualService.translateToText(file, "fr"))
                .isInstanceOf(InvalidDocumentException.class)
                .hasMessageContaining("missing or empty");
    }

    @Test
    void translateToText_fileTooLarge_throwsInvalidDocumentException() {
        TranslationService service = new TranslationService(
                List.of(pdfParser), ocrService, textTranslationService, pdfGenerationService,
                translationJobStore, pdfStorageService, markdownStorageService, new TargetLanguageResolver("fr"), Runnable::run,
                "dual", "1KB", 20000, 1);
        byte[] bigContent = new byte[2048];
        bigContent[0] = '%'; bigContent[1] = 'P'; bigContent[2] = 'D'; bigContent[3] = 'F';
        MockMultipartFile file = new MockMultipartFile("file", "big.pdf", "application/pdf", bigContent);

        assertThatThrownBy(() -> service.translateToText(file, "fr"))
                .isInstanceOf(InvalidDocumentException.class)
                .hasMessageContaining("exceeds maximum");
    }

    @Test
    void translateToText_unsupportedFormat_throwsInvalidDocumentException() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "doc.docx", "application/msword", "not a pdf document".getBytes());

        assertThatThrownBy(() -> dualService.translateToText(file, "fr"))
                .isInstanceOf(InvalidDocumentException.class)
                .hasMessageContaining("Unsupported document format 'text/plain'")
                .hasMessageContaining("Supported formats")
                .hasMessageContaining("application/pdf")
                .hasMessageContaining("image/png");
    }

    // --- parser resolution ---

    @Test
    void translateToText_noContentTypeButPdfMagicBytes_resolvesParserSuccessfully() throws IOException {
        when(pdfParser.open(any())).thenReturn(TestDocumentPages.of(1));
        when(ocrService.extractText(any())).thenReturn("text");
        when(textTranslationService.translatePage(any(), any(TargetLanguage.class), eq(false))).thenReturn("translated");

        MockMultipartFile file = new MockMultipartFile("file", "doc.pdf", null, PDF_BYTES);

        List<String> result = dualService.translateToText(file, "fr");

        assertThat(result).containsExactly("translated");
    }

    @Test
    void resolveParser_pngBytes_resolvesImageParser() throws IOException {
        when(imageParser.open(any())).thenReturn(TestDocumentPages.of(1));
        when(ocrService.extractText(any())).thenReturn("text");
        when(textTranslationService.translatePage(any(), any(TargetLanguage.class), eq(false))).thenReturn("translated");

        MockMultipartFile file = new MockMultipartFile("file", "image.png", "image/png", imageBytes("png"));

        List<String> result = dualService.translateToText(file, "fr");

        assertThat(result).containsExactly("translated");
        verify(imageParser).open(any());
    }

    @Test
    void resolveParser_ignoresTheDeclaredContentType() throws IOException {
        when(imageParser.open(any())).thenReturn(TestDocumentPages.of(1));
        when(ocrService.extractText(any())).thenReturn("text");
        when(textTranslationService.translatePage(any(), any(TargetLanguage.class), eq(false))).thenReturn("translated");
        byte[] jpeg = imageBytes("jpeg");

        // labels guessed by clients: unknown, generic, non-standard, or plain wrong
        for (String label : new String[]{null, "application/octet-stream", "image/pjpeg", "application/pdf"}) {
            dualService.translateToText(new MockMultipartFile("file", "scan", label, jpeg), "fr");
        }

        verify(imageParser, org.mockito.Mockito.times(4)).open(jpeg);
        verify(pdfParser, never()).open(any());
    }

    private static byte[] imageBytes(String format) throws IOException {
        try (var out = new java.io.ByteArrayOutputStream()) {
            javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_RGB), format, out);
            return out.toByteArray();
        }
    }

    // --- corrupt PDF (IOException from open) ---

    @Test
    void translateToText_corruptPdf_throwsInvalidDocumentException() throws IOException {
        when(pdfParser.open(any())).thenThrow(new IOException("corrupt PDF"));

        MockMultipartFile file = new MockMultipartFile("file", "bad.pdf", "application/pdf", PDF_BYTES);

        assertThatThrownBy(() -> dualService.translateToText(file, "fr"))
                .isInstanceOf(InvalidDocumentException.class)
                .hasMessageContaining("invalid or cannot be parsed");
    }

    // --- OCR / LLM failure wrapping ---

    @Test
    void translateToText_ocrThrowsRuntimeException_throwsTranslationProcessingException() throws IOException {
        when(pdfParser.open(any())).thenReturn(TestDocumentPages.of(1));
        when(ocrService.extractText(any())).thenThrow(new RuntimeException("OCR failure"));

        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", PDF_BYTES);

        assertThatThrownBy(() -> dualService.translateToText(file, "fr"))
                .isInstanceOf(TranslationProcessingException.class)
                .hasMessageContaining("Failed to process document translation.")
                .hasCauseInstanceOf(RuntimeException.class);
    }

    @Test
    void translateToText_translationThrowsRuntimeException_throwsTranslationProcessingException() throws IOException {
        when(pdfParser.open(any())).thenReturn(TestDocumentPages.of(1));
        when(ocrService.extractText(any())).thenReturn("extracted");
        when(textTranslationService.translatePage(any(), any(TargetLanguage.class), eq(false))).thenThrow(new RuntimeException("LLM failure"));

        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", PDF_BYTES);

        assertThatThrownBy(() -> dualService.translateToText(file, "fr"))
                .isInstanceOf(TranslationProcessingException.class)
                .hasMessageContaining("Failed to process document translation.");
    }

    @Test
    void translateToText_translationProcessingExceptionPassedThrough() throws IOException {
        when(pdfParser.open(any())).thenReturn(TestDocumentPages.of(1));
        when(ocrService.extractText(any())).thenThrow(
                new TranslationProcessingException("Already wrapped", new RuntimeException()));

        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", PDF_BYTES);

        assertThatThrownBy(() -> dualService.translateToText(file, "fr"))
                .isInstanceOf(TranslationProcessingException.class)
                .hasMessage("Already wrapped");
    }

    // --- strategy selection ---

    @Test
    void translateToText_dualStrategy_callsOcrThenTranslate() throws IOException {
        when(pdfParser.open(any())).thenReturn(TestDocumentPages.of(1));
        when(ocrService.extractText(any())).thenReturn("extracted text");
        when(textTranslationService.translatePage(any(), any(TargetLanguage.class), eq(false))).thenReturn("translated text");

        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", PDF_BYTES);

        List<String> result = dualService.translateToText(file, "de");

        assertThat(result).containsExactly("translated text");
    }

    @Test
    void translateToText_singleStrategy_callsTranslatePagesSingleStrategy() throws IOException {
        when(pdfParser.open(any())).thenReturn(TestDocumentPages.of(1));
        when(textTranslationService.translatePageSingleStrategy(any(), any(TargetLanguage.class), eq(false)))
                .thenReturn("single strategy result");

        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", PDF_BYTES);

        List<String> result = singleService.translateToText(file, "fr");

        assertThat(result).containsExactly("single strategy result");
    }

    // --- translatePlainText ---

    @Test
    void translatePlainText_validInput_returnsStrippedTranslation() {
        when(textTranslationService.translateText("Hello", TargetLanguage.FR)).thenReturn("  Bonjour  ");

        String result = dualService.translatePlainText("Hello", "fr");

        assertThat(result).isEqualTo("Bonjour");
    }

    @Test
    void translatePlainText_nullTargetLanguage_fallsBackToDefaultLanguage() {
        when(textTranslationService.translateText("Hello", TargetLanguage.FR)).thenReturn("Bonjour");

        String result = dualService.translatePlainText("Hello", null);

        assertThat(result).isEqualTo("Bonjour");
    }

    @Test
    void translatePlainText_unsupportedTargetLanguage_fallsBackToDefaultLanguage() {
        when(textTranslationService.translateText("Hello", TargetLanguage.FR)).thenReturn("Bonjour");

        String result = dualService.translatePlainText("Hello", "klingon");

        assertThat(result).isEqualTo("Bonjour");
    }

    @Test
    void translatePlainText_languageName_resolvedToSupportedLanguage() {
        when(textTranslationService.translateText("Hello", TargetLanguage.DE)).thenReturn("Hallo");

        String result = dualService.translatePlainText("Hello", "Deutsch");

        assertThat(result).isEqualTo("Hallo");
    }

    @Test
    void translateToText_resolvesTargetLanguageBeforeCallingLlm() throws IOException {
        when(pdfParser.open(any())).thenReturn(TestDocumentPages.of(1));
        when(ocrService.extractText(any())).thenReturn("text");
        when(textTranslationService.translatePage(any(), eq(TargetLanguage.IT), eq(false))).thenReturn("testo");

        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", PDF_BYTES);

        assertThat(dualService.translateToText(file, "IT")).containsExactly("testo");
    }

    @Test
    void submitPdfTranslation_passesResolvedLanguageToPdfGeneration() throws IOException {
        when(translationJobStore.createPendingJob(JobOutputFormat.PDF))
                .thenReturn(new TranslationJob("job-lang", JobStatus.PENDING, Instant.now(), null, null, JobOutputFormat.PDF));
        when(pdfParser.open(any())).thenReturn(TestDocumentPages.of(1));
        when(ocrService.extractText(any())).thenReturn("text");
        when(textTranslationService.translatePage(any(), eq(TargetLanguage.EN), eq(true))).thenReturn("text");
        when(pdfGenerationService.generatePdf(any(), any(), eq(TargetLanguage.EN))).thenReturn(new byte[]{1});

        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", PDF_BYTES);
        dualService.submitPdfTranslation(file, "english", null);

        verify(pdfGenerationService).generatePdf(any(), any(), eq(TargetLanguage.EN));
        verify(translationJobStore).markCompleted("job-lang");
    }

    @Test
    void translatePlainText_blankText_throwsInvalidDocumentException() {
        assertThatThrownBy(() -> dualService.translatePlainText("   ", "fr"))
                .isInstanceOf(InvalidDocumentException.class)
                .hasMessageContaining("Text is missing or empty");
    }

    @Test
    void translatePlainText_nullText_throwsInvalidDocumentException() {
        assertThatThrownBy(() -> dualService.translatePlainText(null, "fr"))
                .isInstanceOf(InvalidDocumentException.class)
                .hasMessageContaining("Text is missing or empty");
    }

    @Test
    void translatePlainText_textTooLong_throwsInvalidDocumentException() {
        TranslationService service = new TranslationService(
                List.of(pdfParser), ocrService, textTranslationService, pdfGenerationService,
                translationJobStore, pdfStorageService, markdownStorageService, new TargetLanguageResolver("fr"), Runnable::run,
                "dual", "10MB", 10, 1);

        assertThatThrownBy(() -> service.translatePlainText("This text is way too long", "fr"))
                .isInstanceOf(InvalidDocumentException.class)
                .hasMessageContaining("exceeds maximum allowed");
    }

    @Test
    void translatePlainText_llmThrowsRuntimeException_throwsTranslationProcessingException() {
        when(textTranslationService.translateText(anyString(), any(TargetLanguage.class)))
                .thenThrow(new RuntimeException("LLM unavailable"));

        assertThatThrownBy(() -> dualService.translatePlainText("Hello", "fr"))
                .isInstanceOf(TranslationProcessingException.class)
                .hasMessageContaining("Failed to process text translation.");
    }

    @Test
    void translateToTextStream_singleStrategy_mapsImagePreprocessingFailure() throws IOException {
        when(imageParser.open(any())).thenReturn(TestDocumentPages.of(1));
        when(textTranslationService.translatePageSingleStrategyStream(any(), any(TargetLanguage.class)))
                .thenThrow(new ImagePreprocessingException("invalid image"));

        MockMultipartFile file = new MockMultipartFile("file", "test.png", "image/png", imageBytes("png"));

        StepVerifier.create(singleService.translateToTextStream(file, "fr", null))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(TranslationProcessingException.class);
                    assertThat(error).hasCauseInstanceOf(ImagePreprocessingException.class);
                })
                .verify();
    }

    @Test
    void translateToTextStream_dualStrategy_mapsImagePreprocessingFailure() throws IOException {
        when(imageParser.open(any())).thenReturn(TestDocumentPages.of(1));
        when(ocrService.extractText(any())).thenThrow(new ImagePreprocessingException("invalid image"));

        MockMultipartFile file = new MockMultipartFile("file", "test.png", "image/png", imageBytes("png"));

        StepVerifier.create(dualService.translateToTextStream(file, "fr", null))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(TranslationProcessingException.class);
                    assertThat(error).hasCauseInstanceOf(ImagePreprocessingException.class);
                })
                .verify();
    }

    @Test
    void submitPdfTranslation_withExplicitSingleStrategy_overridesDualDefault() throws IOException {
        when(translationJobStore.createPendingJob(JobOutputFormat.PDF)).thenReturn(
                new TranslationJob("job-1", JobStatus.PENDING, java.time.Instant.now(), null, null, JobOutputFormat.PDF));
        when(pdfParser.open(any())).thenReturn(TestDocumentPages.of(1));
        when(textTranslationService.translatePageSingleStrategy(any(), any(TargetLanguage.class), eq(true)))
                .thenReturn("single result");
        when(pdfGenerationService.generatePdf(any(), any(), any())).thenReturn(new byte[]{1, 2, 3});

        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", PDF_BYTES);

        dualService.submitPdfTranslation(file, "fr", TranslationStrategy.SINGLE);

        verify(textTranslationService).translatePageSingleStrategy(any(), any(TargetLanguage.class), eq(true));
        verify(ocrService, never()).extractText(any());
    }

    @Test
    void submitPdfTranslation_withExplicitDualStrategy_overridesSingleDefault() throws IOException {
        when(translationJobStore.createPendingJob(JobOutputFormat.PDF)).thenReturn(
                new TranslationJob("job-2", JobStatus.PENDING, java.time.Instant.now(), null, null, JobOutputFormat.PDF));
        when(pdfParser.open(any())).thenReturn(TestDocumentPages.of(1));
        when(ocrService.extractText(any())).thenReturn("extracted");
        when(textTranslationService.translatePage(any(), any(TargetLanguage.class), eq(true)))
                .thenReturn("dual result");
        when(pdfGenerationService.generatePdf(any(), any(), any())).thenReturn(new byte[]{1, 2, 3});

        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", PDF_BYTES);

        singleService.submitPdfTranslation(file, "fr", TranslationStrategy.DUAL);

        verify(ocrService).extractText(any());
        verify(textTranslationService).translatePage(any(), any(TargetLanguage.class), eq(true));
    }

    @Test
    void submitPdfTranslation_withNullStrategy_fallsBackToDefaultStrategy() throws IOException {
        when(translationJobStore.createPendingJob(JobOutputFormat.PDF)).thenReturn(
                new TranslationJob("job-3", JobStatus.PENDING, java.time.Instant.now(), null, null, JobOutputFormat.PDF));
        when(pdfParser.open(any())).thenReturn(TestDocumentPages.of(1));
        when(textTranslationService.translatePageSingleStrategy(any(), any(TargetLanguage.class), eq(true)))
                .thenReturn("single result");
        when(pdfGenerationService.generatePdf(any(), any(), any())).thenReturn(new byte[]{1, 2, 3});

        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", PDF_BYTES);

        singleService.submitPdfTranslation(file, "fr", null);

        verify(textTranslationService).translatePageSingleStrategy(any(), any(TargetLanguage.class), eq(true));
        verify(ocrService, never()).extractText(any());
    }

    @Test
    void constructor_invalidStrategyValue_throwsException() {
        assertThatThrownBy(() -> new TranslationService(
                List.of(pdfParser), ocrService, textTranslationService, pdfGenerationService,
                translationJobStore, pdfStorageService, markdownStorageService, new TargetLanguageResolver("fr"), Runnable::run,
                "invalid-strategy", "10MB", 20000, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid-strategy");
    }

    // --- translateToPdf ---

    @Test
    void translateToPdf_delegatesToGenerationService() throws IOException {
        byte[] pdfOutput = new byte[]{1, 2, 3};
        when(pdfParser.open(any())).thenReturn(TestDocumentPages.of(1));
        when(ocrService.extractText(any())).thenReturn("text");
        when(textTranslationService.translatePage(any(), any(TargetLanguage.class), eq(true))).thenReturn("translated");
        when(pdfGenerationService.generatePdf(any(List.class), any(List.class), eq(TargetLanguage.FR))).thenReturn(pdfOutput);

        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", PDF_BYTES);

        byte[] result = dualService.translateToPdf(file, "fr");

        assertThat(result).isEqualTo(pdfOutput);
    }

    @Test
    void translateToText_usesPlainTextTranslation_notMarkdown() throws IOException {
        when(pdfParser.open(any())).thenReturn(TestDocumentPages.of(1));
        when(ocrService.extractText(any())).thenReturn("extracted text");
        when(textTranslationService.translatePage(any(), any(TargetLanguage.class), eq(false))).thenReturn("plain text result");

        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", PDF_BYTES);

        List<String> result = dualService.translateToText(file, "fr");

        assertThat(result).containsExactly("plain text result");
        verify(textTranslationService).translatePage(any(), any(TargetLanguage.class), eq(false));
    }

    @Test
    void translateToPdf_usesMarkdownTranslation() throws IOException {
        byte[] pdfOutput = new byte[]{1, 2, 3};
        when(pdfParser.open(any())).thenReturn(TestDocumentPages.of(1));
        when(ocrService.extractText(any())).thenReturn("text");
        when(textTranslationService.translatePage(any(), any(TargetLanguage.class), eq(true))).thenReturn("markdown result");
        when(pdfGenerationService.generatePdf(any(List.class), any(List.class), eq(TargetLanguage.FR))).thenReturn(pdfOutput);

        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", PDF_BYTES);

        dualService.translateToPdf(file, "fr");

        verify(textTranslationService).translatePage(any(), any(TargetLanguage.class), eq(true));
    }

    @Test
    void submitPdfTranslation_processingFails_marksJobFailedWithSafeMessage() throws IOException {
        when(translationJobStore.createPendingJob(JobOutputFormat.PDF)).thenReturn(
                new TranslationJob("123e4567-e89b-12d3-a456-426614174000", JobStatus.PENDING, Instant.now(), null, null, JobOutputFormat.PDF));
        when(pdfParser.open(any())).thenThrow(new IOException("boom"));

        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", PDF_BYTES);

        dualService.submitPdfTranslation(file, "fr", null);

        verify(translationJobStore).markProcessing("123e4567-e89b-12d3-a456-426614174000");
        verify(translationJobStore).markFailed(eq("123e4567-e89b-12d3-a456-426614174000"), eq("PDF translation failed."));
        verify(translationJobStore, never()).markCompleted(anyString());
    }

    @Test
    void submitPdfTranslation_queueFull_removesJobAndThrowsCapacityExceeded() {
        when(translationJobStore.createPendingJob(JobOutputFormat.PDF)).thenReturn(
                new TranslationJob("job-rejected-pdf", JobStatus.PENDING, Instant.now(), null, null, JobOutputFormat.PDF));
        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", PDF_BYTES);

        assertThatThrownBy(() -> serviceWithExecutor(rejectingExecutor()).submitPdfTranslation(file, "fr", null))
                .isInstanceOf(TranslationCapacityExceededException.class)
                .hasCauseInstanceOf(RejectedExecutionException.class);

        verify(translationJobStore).delete("job-rejected-pdf");
        verify(translationJobStore, never()).markProcessing(anyString());
    }

    @Test
    void submitMarkdownTranslation_queueFull_removesJobAndThrowsCapacityExceeded() {
        when(translationJobStore.createPendingJob(JobOutputFormat.MARKDOWN)).thenReturn(
                new TranslationJob("job-rejected-md", JobStatus.PENDING, Instant.now(), null, null, JobOutputFormat.MARKDOWN));
        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", PDF_BYTES);

        assertThatThrownBy(() -> serviceWithExecutor(rejectingExecutor()).submitMarkdownTranslation(file, "fr", null))
                .isInstanceOf(TranslationCapacityExceededException.class);

        verify(translationJobStore).delete("job-rejected-md");
    }

    @Test
    void submitPdfTranslation_processingThrowsError_marksJobFailedAndRethrows() throws IOException {
        when(translationJobStore.createPendingJob(JobOutputFormat.PDF)).thenReturn(
                new TranslationJob("job-oom", JobStatus.PENDING, Instant.now(), null, null, JobOutputFormat.PDF));
        when(pdfParser.open(any())).thenThrow(new OutOfMemoryError("simulated"));
        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", PDF_BYTES);

        // Runnable::run executes the job inline, so the rethrown Error reaches the test
        assertThatThrownBy(() -> dualService.submitPdfTranslation(file, "fr", null))
                .isInstanceOf(OutOfMemoryError.class);

        verify(translationJobStore).markFailed("job-oom", "PDF translation failed.");
        verify(translationJobStore, never()).markCompleted(anyString());
    }

    private TranslationService serviceWithExecutor(Executor executor) {
        return new TranslationService(
                List.of(pdfParser, imageParser), ocrService, textTranslationService, pdfGenerationService,
                translationJobStore, pdfStorageService, markdownStorageService, new TargetLanguageResolver("fr"), executor,
                "dual", "10MB", 20000, 1);
    }

    private static Executor rejectingExecutor() {
        return command -> {
            throw new RejectedExecutionException("queue full");
        };
    }

    // --- markdown translation ---

    @Test
    void submitMarkdownTranslation_success_storesMergedMarkdownAndMarksCompleted() throws IOException {
        when(translationJobStore.createPendingJob(JobOutputFormat.MARKDOWN)).thenReturn(
                new TranslationJob("123e4567-e89b-12d3-a456-426614174001", JobStatus.PENDING, Instant.now(), null, null, JobOutputFormat.MARKDOWN));
        when(pdfParser.open(any())).thenReturn(TestDocumentPages.of(2));
        when(ocrService.extractText(any())).thenReturn("text1", "text2");
        when(textTranslationService.translatePage(any(), any(TargetLanguage.class), eq(true)))
                .thenReturn("# Page 1", "# Page 2");

        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", PDF_BYTES);

        dualService.submitMarkdownTranslation(file, "fr", null);

        verify(translationJobStore).markProcessing("123e4567-e89b-12d3-a456-426614174001");
        verify(markdownStorageService).store(eq("123e4567-e89b-12d3-a456-426614174001"),
                eq("# Page 1\n\n<div style=\"page-break-after: always;\"></div>\n\n# Page 2".getBytes()));
        verify(translationJobStore).markCompleted("123e4567-e89b-12d3-a456-426614174001");
    }

    @Test
    void submitMarkdownTranslation_processingFails_marksJobFailedWithSafeMessage() throws IOException {
        when(translationJobStore.createPendingJob(JobOutputFormat.MARKDOWN)).thenReturn(
                new TranslationJob("123e4567-e89b-12d3-a456-426614174002", JobStatus.PENDING, Instant.now(), null, null, JobOutputFormat.MARKDOWN));
        when(pdfParser.open(any())).thenThrow(new IOException("boom"));

        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", PDF_BYTES);

        dualService.submitMarkdownTranslation(file, "fr", null);

        verify(translationJobStore).markProcessing("123e4567-e89b-12d3-a456-426614174002");
        verify(translationJobStore).markFailed(eq("123e4567-e89b-12d3-a456-426614174002"), eq("Markdown translation failed."));
        verify(translationJobStore, never()).markCompleted(anyString());
    }

    // --- lazy page rendering (one page image in memory at a time) ---

    @Test
    void submitPdfTranslation_rendersEachPageJustBeforeItsTranslation() throws IOException {
        TestDocumentPages pages = TestDocumentPages.withLayouts(new PageLayout(495f, 742f), new PageLayout(842f, 595f),
                new PageLayout(595f, 842f));
        when(translationJobStore.createPendingJob(JobOutputFormat.PDF)).thenReturn(
                new TranslationJob("job-lazy", JobStatus.PENDING, Instant.now(), null, null, JobOutputFormat.PDF));
        when(pdfParser.open(any())).thenReturn(pages);
        List<Integer> renderedWhenTranslated = new java.util.ArrayList<>();
        when(textTranslationService.translatePageSingleStrategy(any(), any(TargetLanguage.class), eq(true))).thenAnswer(invocation -> {
            renderedWhenTranslated.add(pages.renderedPages().size());
            return "page";
        });
        when(pdfGenerationService.generatePdf(any(), any(), any())).thenReturn(new byte[]{1});

        singleService.submitPdfTranslation(new MockMultipartFile("file", "test.pdf", "application/pdf", PDF_BYTES), "fr", null);

        // page n is rendered only after page n-1 was translated: never all pages up front
        assertThat(renderedWhenTranslated).containsExactly(1, 2, 3);
        assertThat(pages.isClosed()).isTrue();
        verify(pdfParser, org.mockito.Mockito.times(1)).open(any());
        // the output PDF gets the displayed page sizes (crop box, rotation) from the same opened document
        verify(pdfGenerationService).generatePdf(eq(List.of("page", "page", "page")),
                eq(List.of(new PageLayout(495f, 742f), new PageLayout(842f, 595f), new PageLayout(595f, 842f))), any());
    }

    @Test
    void submitMarkdownTranslation_dualStrategy_ocrThenTranslatePerPage() throws IOException {
        TestDocumentPages pages = TestDocumentPages.of(2);
        when(translationJobStore.createPendingJob(JobOutputFormat.MARKDOWN)).thenReturn(
                new TranslationJob("job-dual", JobStatus.PENDING, Instant.now(), null, null, JobOutputFormat.MARKDOWN));
        when(pdfParser.open(any())).thenReturn(pages);
        when(ocrService.extractText(pages.image(0))).thenReturn("text1");
        when(ocrService.extractText(pages.image(1))).thenReturn("text2");
        when(textTranslationService.translatePage("text1", TargetLanguage.FR, true)).thenReturn("# One");
        when(textTranslationService.translatePage("text2", TargetLanguage.FR, true)).thenReturn("# Two");

        dualService.submitMarkdownTranslation(new MockMultipartFile("file", "test.pdf", "application/pdf", PDF_BYTES), "fr", null);

        var order = org.mockito.Mockito.inOrder(ocrService, textTranslationService);
        order.verify(ocrService).extractText(pages.image(0));
        order.verify(textTranslationService).translatePage("text1", TargetLanguage.FR, true);
        order.verify(ocrService).extractText(pages.image(1));
        order.verify(textTranslationService).translatePage("text2", TargetLanguage.FR, true);
        assertThat(pages.isClosed()).isTrue();
    }

    @Test
    void translateToTextStream_closesTheDocumentAtTheEnd() throws IOException {
        TestDocumentPages pages = TestDocumentPages.of(2);
        when(pdfParser.open(any())).thenReturn(pages);
        when(textTranslationService.translatePageSingleStrategyStream(any(), any(TargetLanguage.class)))
                .thenAnswer(invocation -> reactor.core.publisher.Flux.just("a", "b"));

        StepVerifier.create(singleService.translateToTextStream(
                        new MockMultipartFile("file", "test.pdf", "application/pdf", PDF_BYTES), "fr", null))
                .expectNextCount(6)   // 2 tokens + page complete, per page
                .verifyComplete();

        assertThat(pages.renderedPages()).containsExactly(0, 1);
        assertThat(pages.isClosed()).isTrue();
    }

    @Test
    void translateToPdf_pageCannotBeRendered_invalidDocument() throws IOException {
        TestDocumentPages pages = TestDocumentPages.of(2).failingRender(new IOException("broken page"));
        when(pdfParser.open(any())).thenReturn(pages);

        assertThatThrownBy(() -> dualService.translateToPdf(
                new MockMultipartFile("file", "test.pdf", "application/pdf", PDF_BYTES), "fr"))
                .isInstanceOf(InvalidDocumentException.class);
        assertThat(pages.isClosed()).isTrue();
    }

    // --- parallel pages (zia.translation.pages-in-flight) ---

    private TranslationService parallelService(String strategy, int pagesInFlight) {
        return new TranslationService(
                List.of(pdfParser, imageParser), ocrService, textTranslationService, pdfGenerationService,
                translationJobStore, pdfStorageService, markdownStorageService, new TargetLanguageResolver("fr"), Runnable::run,
                strategy, "10MB", 20000, pagesInFlight);
    }

    /** Page number (from 1) of a TestDocumentPages image: each page image holds its number in its single pixel. */
    private static int pageOf(Object image) {
        return ((java.awt.image.BufferedImage) image).getRGB(0, 0) & 0xFFFFFF;
    }

    @Test
    void translatePages_parallel_keepsPageOrderAndNeverExceedsPagesInFlight() throws IOException {
        TestDocumentPages pages = TestDocumentPages.of(10);
        when(pdfParser.open(any())).thenReturn(pages);
        AtomicInteger inProgress = new AtomicInteger();
        AtomicInteger maxInProgress = new AtomicInteger();
        when(textTranslationService.translatePageSingleStrategy(any(), any(TargetLanguage.class), eq(false))).thenAnswer(invocation -> {
            maxInProgress.accumulateAndGet(inProgress.incrementAndGet(), Math::max);
            int page = pageOf(invocation.getArgument(0));
            Thread.sleep(10 + (page * 7) % 30);   // pages finish out of order
            inProgress.decrementAndGet();
            return "page-" + page;
        });

        List<String> result = parallelService("single", 4).translateToText(
                new MockMultipartFile("file", "test.pdf", "application/pdf", PDF_BYTES), "fr");

        assertThat(result).containsExactly("page-1", "page-2", "page-3", "page-4", "page-5",
                "page-6", "page-7", "page-8", "page-9", "page-10");
        assertThat(maxInProgress.get()).isEqualTo(4);
        assertThat(pages.renderedPages()).containsExactlyInAnyOrder(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
        assertThat(pages.isClosed()).isTrue();
    }

    @Test
    void translatePages_parallelDualStrategy_eachPageOcrThenTranslate() throws IOException {
        when(pdfParser.open(any())).thenReturn(TestDocumentPages.of(6));
        when(ocrService.extractText(any())).thenAnswer(invocation -> "text-" + pageOf(invocation.getArgument(0)));
        when(textTranslationService.translatePage(any(), any(TargetLanguage.class), eq(false)))
                .thenAnswer(invocation -> "T(" + invocation.getArgument(0) + ")");

        List<String> result = parallelService("dual", 3).translateToText(
                new MockMultipartFile("file", "test.pdf", "application/pdf", PDF_BYTES), "fr");

        assertThat(result).containsExactly("T(text-1)", "T(text-2)", "T(text-3)", "T(text-4)", "T(text-5)", "T(text-6)");
    }

    @Test
    void translatePages_failingPage_failsTheDocumentAndStopsTheOtherPages() throws IOException {
        TestDocumentPages pages = TestDocumentPages.of(20);
        when(pdfParser.open(any())).thenReturn(pages);
        AtomicInteger inProgress = new AtomicInteger();
        AtomicInteger started = new AtomicInteger();
        when(textTranslationService.translatePageSingleStrategy(any(), any(TargetLanguage.class), eq(false))).thenAnswer(invocation -> {
            started.incrementAndGet();
            inProgress.incrementAndGet();
            try {
                if (pageOf(invocation.getArgument(0)) == 3) {
                    throw new IllegalStateException("model error on page 3");
                }
                Thread.sleep(2_000);   // a long model call: interrupted when page 3 fails
                return "page";
            } finally {
                inProgress.decrementAndGet();
            }
        });
        long start = System.nanoTime();

        assertThatThrownBy(() -> parallelService("single", 4).translateToText(
                new MockMultipartFile("file", "test.pdf", "application/pdf", PDF_BYTES), "fr"))
                .isInstanceOf(TranslationProcessingException.class)
                .hasRootCauseMessage("model error on page 3");

        assertThat(inProgress.get()).as("model calls still running after the failure").isZero();
        assertThat(started.get()).as("pages started").isLessThan(20);
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - start)).isLessThan(java.time.Duration.ofSeconds(2));
        assertThat(pages.isClosed()).isTrue();
    }

    @Test
    void translateToTextStream_parallel_pagesStillEmittedInOrder() throws IOException {
        when(pdfParser.open(any())).thenReturn(TestDocumentPages.of(5));
        when(textTranslationService.translatePageSingleStrategyStream(any(), any(TargetLanguage.class))).thenAnswer(invocation -> {
            int page = pageOf(invocation.getArgument(0));
            // later pages answer faster than earlier ones
            return Flux.just("p" + page + "a", "p" + page + "b").delayElements(java.time.Duration.ofMillis(60 - page * 10L));
        });

        List<Integer> pageOfEachEvent = parallelService("single", 4)
                .translateToTextStream(new MockMultipartFile("file", "test.pdf", "application/pdf", PDF_BYTES), "fr", null)
                .map(event -> switch (event) {
                    case TranslationStreamEvent.Token token -> token.pageNumber();
                    case TranslationStreamEvent.PageComplete complete -> complete.pageNumber();
                })
                .collectList()
                .block(java.time.Duration.ofSeconds(5));

        assertThat(pageOfEachEvent).containsExactly(1, 1, 1, 2, 2, 2, 3, 3, 3, 4, 4, 4, 5, 5, 5);
    }

    @Test
    void constructor_invalidPagesInFlight_throwsException() {
        assertThatThrownBy(() -> parallelService("dual", 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mergeMarkdownPages_multiplePages_joinsWithPageBreakSeparator() {
        String merged = TranslationService.mergeMarkdownPages(List.of("Page 1", "Page 2", "Page 3"));

        assertThat(merged).isEqualTo(
                "Page 1\n\n<div style=\"page-break-after: always;\"></div>\n\nPage 2\n\n<div style=\"page-break-after: always;\"></div>\n\nPage 3");
    }

    @Test
    void mergeMarkdownPages_singlePage_noSeparatorAdded() {
        String merged = TranslationService.mergeMarkdownPages(List.of("Only page"));

        assertThat(merged).isEqualTo("Only page");
    }

    @Test
    void mergeMarkdownPages_zeroPages_returnsEmptyString() {
        String merged = TranslationService.mergeMarkdownPages(List.of());

        assertThat(merged).isEmpty();
    }

    // --- getTranslatedFile ---

    @Test
    void getTranslatedFile_pdfJob_resolvesFromPdfStorageWithPdfContentType() {
        TranslationJob job = new TranslationJob(
                "123e4567-e89b-12d3-a456-426614174003", JobStatus.COMPLETED, Instant.now(), Instant.now(), null, JobOutputFormat.PDF);
        when(translationJobStore.findById(job.jobId())).thenReturn(java.util.Optional.of(job));
        byte[] pdfBytes = {1, 2, 3};
        when(pdfStorageService.load(job.jobId())).thenReturn(java.util.Optional.of(new org.springframework.core.io.ByteArrayResource(pdfBytes)));

        var result = dualService.getTranslatedFile(job.jobId());

        assertThat(result).isPresent();
        assertThat(result.get().mediaType()).isEqualTo(org.springframework.http.MediaType.APPLICATION_PDF);
        assertThat(result.get().filename()).isEqualTo(job.jobId() + ".pdf");
    }

    @Test
    void getTranslatedFile_markdownJob_resolvesFromMarkdownStorageWithMarkdownContentType() {
        TranslationJob job = new TranslationJob(
                "123e4567-e89b-12d3-a456-426614174004", JobStatus.COMPLETED, Instant.now(), Instant.now(), null, JobOutputFormat.MARKDOWN);
        when(translationJobStore.findById(job.jobId())).thenReturn(java.util.Optional.of(job));
        byte[] mdBytes = "# Titre".getBytes();
        when(markdownStorageService.load(job.jobId())).thenReturn(java.util.Optional.of(new org.springframework.core.io.ByteArrayResource(mdBytes)));

        var result = dualService.getTranslatedFile(job.jobId());

        assertThat(result).isPresent();
        assertThat(result.get().mediaType()).isEqualTo(new org.springframework.http.MediaType("text", "markdown"));
        assertThat(result.get().filename()).isEqualTo(job.jobId() + ".md");
    }

    @Test
    void getTranslatedFile_unknownJob_returnsEmpty() {
        when(translationJobStore.findById("missing")).thenReturn(java.util.Optional.empty());

        assertThat(dualService.getTranslatedFile("missing")).isEmpty();
    }

    @Test
    void constructor_duplicateMimeType_throwsClearException() {
        DocumentParser duplicateParser = mock(DocumentParser.class);
        when(duplicateParser.supportedMimeTypes()).thenReturn(List.of("image/png"));

        assertThatThrownBy(() -> new TranslationService(
                List.of(pdfParser, imageParser, duplicateParser), ocrService, textTranslationService, pdfGenerationService,
                translationJobStore, pdfStorageService, markdownStorageService, new TargetLanguageResolver("fr"), Runnable::run,
                "dual", "10MB", 20000, 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate parser mapping for MIME type 'image/png'");
    }
}
