package zas.admin.zia.translation.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import zas.admin.zia.translation.service.llm.TextTranslationService;
import zas.admin.zia.translation.service.ocr.OcrExtractionService;
import zas.admin.zia.translation.service.job.JobStatus;
import zas.admin.zia.translation.service.job.TranslationJob;
import zas.admin.zia.translation.service.job.TranslationJobStore;
import zas.admin.zia.translation.service.parser.DocumentParser;
import zas.admin.zia.translation.service.parser.PageLayout;
import zas.admin.zia.translation.service.pdf.PdfGenerationService;
import zas.admin.zia.translation.service.storage.PdfStorageService;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
                translationJobStore, pdfStorageService, Runnable::run,
                TranslationService.STRATEGY_DUAL, "10MB");

        singleService = new TranslationService(
                List.of(pdfParser, imageParser), ocrService, textTranslationService, pdfGenerationService,
                translationJobStore, pdfStorageService, Runnable::run,
                TranslationService.STRATEGY_SINGLE, "10MB");
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
                translationJobStore, pdfStorageService, Runnable::run,
                TranslationService.STRATEGY_DUAL, "1KB");
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
                .hasMessageContaining("Unsupported file format")
                .hasMessageContaining("Supported formats")
                .hasMessageContaining("application/pdf")
                .hasMessageContaining("image/png");
    }

    // --- parser resolution ---

    @Test
    void translateToText_noContentTypeButPdfMagicBytes_resolvesParserSuccessfully() throws IOException {
        when(pdfParser.renderPages(any())).thenReturn(List.of(new byte[]{1}));
        when(ocrService.extractText(any())).thenReturn(List.of("text"));
        when(textTranslationService.translatePages(any(), anyString(), eq(false))).thenReturn(List.of("translated"));

        MockMultipartFile file = new MockMultipartFile("file", "doc.pdf", null, PDF_BYTES);

        List<String> result = dualService.translateToText(file, "fr");

        assertThat(result).containsExactly("translated");
    }

    @Test
    void resolveParser_imageContentType_resolvesImageParser() throws IOException {
        when(imageParser.renderPages(any())).thenReturn(List.of(new byte[]{1}));
        when(ocrService.extractText(any())).thenReturn(List.of("text"));
        when(textTranslationService.translatePages(any(), anyString(), eq(false))).thenReturn(List.of("translated"));

        MockMultipartFile file = new MockMultipartFile("file", "image.png", "image/png", new byte[]{1, 2, 3});

        List<String> result = dualService.translateToText(file, "fr");

        assertThat(result).containsExactly("translated");
        verify(imageParser).renderPages(any());
    }

    // --- corrupt PDF (IOException from renderPages) ---

    @Test
    void translateToText_corruptPdf_throwsInvalidDocumentException() throws IOException {
        when(pdfParser.renderPages(any())).thenThrow(new IOException("corrupt PDF"));

        MockMultipartFile file = new MockMultipartFile("file", "bad.pdf", "application/pdf", PDF_BYTES);

        assertThatThrownBy(() -> dualService.translateToText(file, "fr"))
                .isInstanceOf(InvalidDocumentException.class)
                .hasMessageContaining("invalid or cannot be parsed");
    }

    // --- OCR / LLM failure wrapping ---

    @Test
    void translateToText_ocrThrowsRuntimeException_throwsTranslationProcessingException() throws IOException {
        when(pdfParser.renderPages(any())).thenReturn(List.of(new byte[]{1}));
        when(ocrService.extractText(any())).thenThrow(new RuntimeException("OCR failure"));

        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", PDF_BYTES);

        assertThatThrownBy(() -> dualService.translateToText(file, "fr"))
                .isInstanceOf(TranslationProcessingException.class)
                .hasMessageContaining("Failed to process document translation.")
                .hasCauseInstanceOf(RuntimeException.class);
    }

    @Test
    void translateToText_translationThrowsRuntimeException_throwsTranslationProcessingException() throws IOException {
        when(pdfParser.renderPages(any())).thenReturn(List.of(new byte[]{1}));
        when(ocrService.extractText(any())).thenReturn(List.of("extracted"));
        when(textTranslationService.translatePages(any(), anyString(), eq(false))).thenThrow(new RuntimeException("LLM failure"));

        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", PDF_BYTES);

        assertThatThrownBy(() -> dualService.translateToText(file, "fr"))
                .isInstanceOf(TranslationProcessingException.class)
                .hasMessageContaining("Failed to process document translation.");
    }

    @Test
    void translateToText_translationProcessingExceptionPassedThrough() throws IOException {
        when(pdfParser.renderPages(any())).thenReturn(List.of(new byte[]{1}));
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
        when(pdfParser.renderPages(any())).thenReturn(List.of(new byte[]{1}));
        when(ocrService.extractText(any())).thenReturn(List.of("extracted text"));
        when(textTranslationService.translatePages(any(), anyString(), eq(false))).thenReturn(List.of("translated text"));

        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", PDF_BYTES);

        List<String> result = dualService.translateToText(file, "de");

        assertThat(result).containsExactly("translated text");
    }

    @Test
    void translateToText_singleStrategy_callsTranslatePagesSingleStrategy() throws IOException {
        when(pdfParser.renderPages(any())).thenReturn(List.of(new byte[]{1}));
        when(textTranslationService.translatePagesSingleStrategy(any(), anyString(), eq(false)))
                .thenReturn(List.of("single strategy result"));

        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", PDF_BYTES);

        List<String> result = singleService.translateToText(file, "fr");

        assertThat(result).containsExactly("single strategy result");
    }

    // --- translateToPdf ---

    @Test
    void translateToPdf_delegatesToGenerationService() throws IOException {
        byte[] pdfOutput = new byte[]{1, 2, 3};
        when(pdfParser.renderPages(any())).thenReturn(List.of(new byte[]{1}));
        when(pdfParser.extractPageLayouts(any())).thenReturn(List.of(new PageLayout(595f, 842f)));
        when(ocrService.extractText(any())).thenReturn(List.of("text"));
        when(textTranslationService.translatePages(any(), anyString(), eq(true))).thenReturn(List.of("translated"));
        when(pdfGenerationService.generatePdf(any(List.class), any(List.class))).thenReturn(pdfOutput);

        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", PDF_BYTES);

        byte[] result = dualService.translateToPdf(file, "fr");

        assertThat(result).isEqualTo(pdfOutput);
    }

    @Test
    void translateToText_usesPlainTextTranslation_notMarkdown() throws IOException {
        when(pdfParser.renderPages(any())).thenReturn(List.of(new byte[]{1}));
        when(ocrService.extractText(any())).thenReturn(List.of("extracted text"));
        when(textTranslationService.translatePages(any(), anyString(), eq(false))).thenReturn(List.of("plain text result"));

        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", PDF_BYTES);

        List<String> result = dualService.translateToText(file, "fr");

        assertThat(result).containsExactly("plain text result");
        verify(textTranslationService).translatePages(any(), anyString(), eq(false));
    }

    @Test
    void translateToPdf_usesMarkdownTranslation() throws IOException {
        byte[] pdfOutput = new byte[]{1, 2, 3};
        when(pdfParser.renderPages(any())).thenReturn(List.of(new byte[]{1}));
        when(pdfParser.extractPageLayouts(any())).thenReturn(List.of(new PageLayout(595f, 842f)));
        when(ocrService.extractText(any())).thenReturn(List.of("text"));
        when(textTranslationService.translatePages(any(), anyString(), eq(true))).thenReturn(List.of("markdown result"));
        when(pdfGenerationService.generatePdf(any(List.class), any(List.class))).thenReturn(pdfOutput);

        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", PDF_BYTES);

        dualService.translateToPdf(file, "fr");

        verify(textTranslationService).translatePages(any(), anyString(), eq(true));
    }

    @Test
    void submitPdfTranslation_processingFails_marksJobFailedWithSafeMessage() throws IOException {
        when(translationJobStore.createPendingJob()).thenReturn(
                new TranslationJob("123e4567-e89b-12d3-a456-426614174000", JobStatus.PENDING, Instant.now(), null, null));
        when(pdfParser.extractPageLayouts(any())).thenThrow(new IOException("boom"));

        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", PDF_BYTES);

        dualService.submitPdfTranslation(file, "fr");

        verify(translationJobStore).markProcessing("123e4567-e89b-12d3-a456-426614174000");
        verify(translationJobStore).markFailed(eq("123e4567-e89b-12d3-a456-426614174000"), eq("PDF translation failed."));
        verify(translationJobStore, never()).markCompleted(anyString());
    }

    @Test
    void constructor_duplicateMimeType_throwsClearException() {
        DocumentParser duplicateParser = org.mockito.Mockito.mock(DocumentParser.class);
        when(duplicateParser.supportedMimeTypes()).thenReturn(List.of("image/png"));

        assertThatThrownBy(() -> new TranslationService(
                List.of(pdfParser, imageParser, duplicateParser), ocrService, textTranslationService, pdfGenerationService,
                translationJobStore, pdfStorageService, Runnable::run,
                TranslationService.STRATEGY_DUAL, "10MB"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate parser mapping for MIME type 'image/png'");
    }
}
