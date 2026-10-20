package zas.admin.zia.translation.service.extraction;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;
import tools.jackson.databind.json.JsonMapper;
import zas.admin.zia.translation.service.InvalidDocumentException;
import zas.admin.zia.translation.service.parser.DocumentParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExtractionServiceTest {

    private static final byte[] PDF = "%PDF-1.7 content".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
    private static final ExtractionContext CONTEXT = new ExtractionContext("sumex-invoice", "D_0035", "de",
            Map.of(), List.of("biller.gln", "treatment.date_begin", "services", "not.a.key"), null);

    private static ExtractionRequestResolver resolver;

    private ExtractionJobStore jobStore;
    private VisionFieldExtractor extractor;
    private DocumentParser pdfParser;
    private DocumentParser imageParser;
    private final List<Runnable> scheduled = new ArrayList<>();
    private Executor executor = scheduled::add;

    @BeforeEach
    void setUp() throws IOException {
        if (resolver == null) {
            resolver = new ExtractionRequestResolver(
                    new ExtractionProfileRegistry(JsonMapper.builder().build(), ""), 80, 2000);
        }
        jobStore = new ExtractionJobStore();
        extractor = mock(VisionFieldExtractor.class);
        pdfParser = mock(DocumentParser.class);
        when(pdfParser.supportedMimeTypes()).thenReturn(List.of("application/pdf"));
        imageParser = mock(DocumentParser.class);
        when(imageParser.supportedMimeTypes()).thenReturn(List.of("image/png", "image/jpeg"));
    }

    private ExtractionService service(int maxPages) {
        return new ExtractionService(jobStore, resolver, extractor, List.of(pdfParser, imageParser), executor,
                DataSize.ofKilobytes(1), maxPages);
    }

    private static MockMultipartFile file(byte[] bytes, String contentType) {
        return new MockMultipartFile("file", "document", contentType, bytes);
    }

    private static MockMultipartFile pdf(byte[] bytes) {
        return file(bytes, "application/pdf");
    }

    @Test
    void submit_createsPendingJobAndSchedulesProcessing() throws IOException {
        ExtractionJobResponse response = service(20).submit("key-1", pdf(PDF), CONTEXT);

        assertThat(response.status()).isEqualTo(ExtractionStatus.PENDING);
        assertThat(response.jobId()).isNotBlank();
        assertThat(response.submittedAt()).isNotNull();
        assertThat(scheduled).hasSize(1);
    }

    @Test
    void submit_sameKey_returnsSameJobWithoutReprocessing() throws IOException {
        ExtractionService service = service(20);
        ExtractionJobResponse first = service.submit("key-1", pdf(PDF), CONTEXT);

        ExtractionJobResponse second = service.submit("key-1", pdf(PDF), CONTEXT);

        assertThat(second.jobId()).isEqualTo(first.jobId());
        assertThat(scheduled).hasSize(1);
    }

    @Test
    void submit_invalidRequests_areRejected() {
        ExtractionService service = service(20);
        ExtractionContext noKey = new ExtractionContext(null, null, null, null, List.of(), null);

        assertThatThrownBy(() -> service.submit(" ", pdf(PDF), CONTEXT)).isInstanceOf(InvalidDocumentException.class);
        assertThatThrownBy(() -> service.submit("k".repeat(129), pdf(PDF), CONTEXT))
                .isInstanceOf(InvalidDocumentException.class);
        assertThatThrownBy(() -> service.submit("key", pdf(new byte[0]), CONTEXT))
                .isInstanceOf(InvalidDocumentException.class);
        assertThatThrownBy(() -> service.submit("key", pdf("not a pdf".getBytes()), CONTEXT))
                .isInstanceOf(InvalidDocumentException.class);
        assertThatThrownBy(() -> service.submit("key", file("text".getBytes(), "text/plain"), CONTEXT))
                .isInstanceOf(InvalidDocumentException.class);
        assertThatThrownBy(() -> service.submit("key", pdf(PDF), null))
                .isInstanceOf(InvalidDocumentException.class);
        assertThatThrownBy(() -> service.submit("key", pdf(PDF), noKey))
                .isInstanceOf(InvalidDocumentException.class);
        assertThat(scheduled).isEmpty();
    }

    @Test
    void submit_documentTooLarge_isRejected() {
        byte[] large = new byte[2048];
        System.arraycopy(PDF, 0, large, 0, PDF.length);

        assertThatThrownBy(() -> service(20).submit("key", pdf(large), CONTEXT))
                .isInstanceOf(ExtractionService.DocumentTooLargeException.class);
    }

    @Test
    void submit_executorSaturated_forgetsJobAndThrows() throws IOException {
        executor = task -> {
            throw new RejectedExecutionException("full");
        };
        ExtractionService service = service(20);

        assertThatThrownBy(() -> service.submit("key", pdf(PDF), CONTEXT))
                .isInstanceOf(ExtractionService.ExtractionCapacityExceededException.class);

        executor = scheduled::add;
        assertThat(service(20).submit("key", pdf(PDF), CONTEXT).status()).isEqualTo(ExtractionStatus.PENDING);
        assertThat(scheduled).hasSize(1);
    }

    @Test
    void selectParser_usesContentSignatureThenDeclaredType() {
        ExtractionService service = service(20);

        assertThat(service.selectParser(PDF, "application/octet-stream")).isSameAs(pdfParser);
        assertThat(service.selectParser(PNG, "application/pdf")).isSameAs(imageParser);
        assertThat(service.selectParser(new byte[]{1, 2}, "image/jpeg")).isSameAs(imageParser);
        assertThatThrownBy(() -> service.selectParser(new byte[]{1, 2}, "application/pdf"))
                .isInstanceOf(InvalidDocumentException.class);
        assertThatThrownBy(() -> service.selectParser(new byte[]{1, 2}, null))
                .isInstanceOf(InvalidDocumentException.class);
    }

    @Test
    void process_extractsEachPageAndMergesResult() throws IOException {
        when(pdfParser.renderPages(PDF)).thenReturn(List.of(new byte[]{1}, new byte[]{2}));
        when(extractor.extract(any(), eq(1), eq(2), any()))
                .thenReturn(new PageExtraction(1, "invoice",
                        Map.of("biller.gln", new ExtractedField("7601000152916", 0.6, 1, "7601000152916")),
                        Map.of(), List.of()));
        when(extractor.extract(any(), eq(2), eq(2), any()))
                .thenReturn(new PageExtraction(2, null,
                        Map.of("biller.gln", new ExtractedField("7601000152916", 0.9, 2, "7601000152916"),
                                "treatment.date_begin", new ExtractedField("2026-08-26", 0.8, 2, "26.08.2026")),
                        Map.of("services", List.of(Map.of("code", new ExtractedField("1001", 0.9, 2, "1001")))),
                        List.of()));
        ExtractionService service = service(20);
        String jobId = service.submit("key", pdf(PDF), CONTEXT).jobId();

        scheduled.getFirst().run();

        ExtractionJobResponse job = service.find(jobId).orElseThrow();
        assertThat(job.status()).isEqualTo(ExtractionStatus.SUCCEEDED);
        assertThat(job.result().profile()).isEqualTo("sumex-invoice");
        assertThat(job.result().documentType()).isEqualTo("invoice");
        assertThat(job.result().fields()).containsOnlyKeys("biller.gln", "treatment.date_begin");
        assertThat(job.result().fields().get("biller.gln").page()).isEqualTo(2);
        assertThat(job.result().collections().get("services")).hasSize(1);
        assertThat(job.result().warnings()).containsExactly("Keys unknown to profile 'sumex-invoice' ignored: not.a.key");
        assertThat(job.error()).isNull();
        verify(extractor).extract(any(), eq(1), eq(2), argThat(request ->
                request.fields().size() == 2 && request.collections().size() == 1));
    }

    @Test
    void process_image_usesImageParser() throws IOException {
        when(imageParser.renderPages(PNG)).thenReturn(List.of(PNG));
        when(extractor.extract(any(), anyInt(), anyInt(), any()))
                .thenReturn(new PageExtraction(1, null, Map.of(), Map.of(), List.of()));
        ExtractionService service = service(20);
        String jobId = service.submit("key", file(PNG, "image/png"), CONTEXT).jobId();

        scheduled.getFirst().run();

        assertThat(service.find(jobId).orElseThrow().status()).isEqualTo(ExtractionStatus.SUCCEEDED);
        verify(pdfParser, never()).renderPages(any());
    }

    @Test
    void process_pageFailure_isReportedAsWarning() throws IOException {
        when(pdfParser.renderPages(PDF)).thenReturn(List.of(new byte[]{1}, new byte[]{2}));
        when(extractor.extract(any(), eq(1), anyInt(), any()))
                .thenThrow(new VisionFieldExtractor.PageExtractionException("bad json", null));
        when(extractor.extract(any(), eq(2), anyInt(), any()))
                .thenReturn(new PageExtraction(2, null, Map.of(), Map.of(), List.of()));
        ExtractionService service = service(20);
        String jobId = service.submit("key", pdf(PDF), CONTEXT).jobId();

        scheduled.getFirst().run();

        ExtractionJobResponse job = service.find(jobId).orElseThrow();
        assertThat(job.status()).isEqualTo(ExtractionStatus.SUCCEEDED);
        assertThat(job.result().warnings()).contains("Page 1 could not be analysed by the extraction service");
    }

    @Test
    void process_allPagesFail_marksJobFailed() throws IOException {
        when(pdfParser.renderPages(PDF)).thenReturn(List.of(new byte[]{1}));
        when(extractor.extract(any(), anyInt(), anyInt(), any()))
                .thenThrow(new VisionFieldExtractor.PageExtractionException("bad json", null));
        ExtractionService service = service(20);
        String jobId = service.submit("key", pdf(PDF), CONTEXT).jobId();

        scheduled.getFirst().run();

        ExtractionJobResponse job = service.find(jobId).orElseThrow();
        assertThat(job.status()).isEqualTo(ExtractionStatus.FAILED);
        assertThat(job.error().code()).isEqualTo(ExtractionError.EXTRACTION_FAILED);
        assertThat(job.result()).isNull();
    }

    @Test
    void process_unreadableDocument_marksJobFailed() throws IOException {
        when(pdfParser.renderPages(PDF)).thenThrow(new IOException("corrupted"));
        ExtractionService service = service(20);
        String jobId = service.submit("key", pdf(PDF), CONTEXT).jobId();

        scheduled.getFirst().run();

        ExtractionJobResponse job = service.find(jobId).orElseThrow();
        assertThat(job.status()).isEqualTo(ExtractionStatus.FAILED);
        assertThat(job.error().code()).isEqualTo(ExtractionError.UNREADABLE_DOCUMENT);
        verify(extractor, never()).extract(any(), anyInt(), anyInt(), any());
    }

    @Test
    void process_tooManyPages_analysesOnlyMaxPages() throws IOException {
        when(pdfParser.renderPages(PDF)).thenReturn(List.of(new byte[]{1}, new byte[]{2}, new byte[]{3}));
        when(extractor.extract(any(), anyInt(), anyInt(), any()))
                .thenReturn(new PageExtraction(1, null, Map.of(), Map.of(), List.of()));
        ExtractionService service = service(2);
        String jobId = service.submit("key", pdf(PDF), CONTEXT).jobId();

        scheduled.getFirst().run();

        verify(extractor, times(2)).extract(any(), anyInt(), eq(3), any());
        assertThat(service.find(jobId).orElseThrow().result().warnings())
                .contains("Only the first 2 of 3 pages were analysed");
    }

    @Test
    void detectMimeType_recognizesSignatures() {
        assertThat(ExtractionService.detectMimeType("\n\n%PDF-1.4".getBytes(StandardCharsets.US_ASCII)))
                .isEqualTo("application/pdf");
        assertThat(ExtractionService.detectMimeType(PNG)).isEqualTo("image/png");
        assertThat(ExtractionService.detectMimeType(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0}))
                .isEqualTo("image/jpeg");
        assertThat(ExtractionService.detectMimeType("GIF89a".getBytes(StandardCharsets.US_ASCII))).isEqualTo("image/gif");
        assertThat(ExtractionService.detectMimeType("BM..".getBytes(StandardCharsets.US_ASCII))).isEqualTo("image/bmp");
        assertThat(ExtractionService.detectMimeType("%PD".getBytes(StandardCharsets.US_ASCII))).isNull();
    }
}
