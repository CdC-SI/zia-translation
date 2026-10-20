package zas.admin.zia.translation.service.invoice;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;
import zas.admin.zia.translation.service.InvalidDocumentException;
import zas.admin.zia.translation.service.parser.DocumentParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InvoiceExtractionServiceTest {

    private static final byte[] PDF = "%PDF-1.7 content".getBytes(StandardCharsets.US_ASCII);
    private static final ExtractionContext CONTEXT = new ExtractionContext("D_0035", "de", "301",
            Map.of(), List.of("biller.gln", "treatment.date_begin", "services", "not.a.key"));

    private InvoiceExtractionJobStore jobStore;
    private VisionInvoiceFieldExtractor extractor;
    private DocumentParser pdfParser;
    private final List<Runnable> scheduled = new ArrayList<>();
    private Executor executor = scheduled::add;

    @BeforeEach
    void setUp() {
        jobStore = new InvoiceExtractionJobStore();
        extractor = mock(VisionInvoiceFieldExtractor.class);
        pdfParser = mock(DocumentParser.class);
        when(pdfParser.supportedMimeTypes()).thenReturn(List.of("application/pdf"));
    }

    private InvoiceExtractionService service(int maxPages) {
        return new InvoiceExtractionService(jobStore, extractor, List.of(pdfParser), executor,
                DataSize.ofKilobytes(1), maxPages);
    }

    private static MockMultipartFile pdf(byte[] bytes) {
        return new MockMultipartFile("file", "invoice.pdf", "application/pdf", bytes);
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
        InvoiceExtractionService service = service(20);
        ExtractionJobResponse first = service.submit("key-1", pdf(PDF), CONTEXT);

        ExtractionJobResponse second = service.submit("key-1", pdf(PDF), CONTEXT);

        assertThat(second.jobId()).isEqualTo(first.jobId());
        assertThat(scheduled).hasSize(1);
    }

    @Test
    void submit_invalidRequests_areRejected() {
        InvoiceExtractionService service = service(20);

        assertThatThrownBy(() -> service.submit(" ", pdf(PDF), CONTEXT)).isInstanceOf(InvalidDocumentException.class);
        assertThatThrownBy(() -> service.submit("k".repeat(129), pdf(PDF), CONTEXT))
                .isInstanceOf(InvalidDocumentException.class);
        assertThatThrownBy(() -> service.submit("key", pdf(new byte[0]), CONTEXT))
                .isInstanceOf(InvalidDocumentException.class);
        assertThatThrownBy(() -> service.submit("key", pdf("not a pdf".getBytes()), CONTEXT))
                .isInstanceOf(InvalidDocumentException.class);
        assertThatThrownBy(() -> service.submit("key", pdf(PDF), null))
                .isInstanceOf(InvalidDocumentException.class);
        assertThat(scheduled).isEmpty();
    }

    @Test
    void submit_documentTooLarge_isRejected() {
        byte[] large = new byte[2048];
        System.arraycopy(PDF, 0, large, 0, PDF.length);

        assertThatThrownBy(() -> service(20).submit("key", pdf(large), CONTEXT))
                .isInstanceOf(InvoiceExtractionService.DocumentTooLargeException.class);
    }

    @Test
    void submit_executorSaturated_forgetsJobAndThrows() throws IOException {
        executor = task -> {
            throw new RejectedExecutionException("full");
        };
        InvoiceExtractionService service = service(20);

        assertThatThrownBy(() -> service.submit("key", pdf(PDF), CONTEXT))
                .isInstanceOf(InvoiceExtractionService.ExtractionCapacityExceededException.class);

        executor = scheduled::add;
        assertThat(service(20).submit("key", pdf(PDF), CONTEXT).status()).isEqualTo(ExtractionStatus.PENDING);
        assertThat(scheduled).hasSize(1);
    }

    @Test
    void process_extractsEachPageAndMergesResult() throws IOException {
        when(pdfParser.renderPages(PDF)).thenReturn(List.of(new byte[]{1}, new byte[]{2}));
        Set<String> requested = Set.of("biller.gln", "treatment.date_begin");
        when(extractor.extract(any(), eq(1), eq(2), eq(CONTEXT), eq(requested), eq(true)))
                .thenReturn(new PageExtraction(1, "invoice",
                        Map.of("biller.gln", new ExtractedField("7601000152916", 0.6, 1)), List.of(), List.of()));
        when(extractor.extract(any(), eq(2), eq(2), eq(CONTEXT), eq(requested), eq(true)))
                .thenReturn(new PageExtraction(2, null,
                        Map.of("biller.gln", new ExtractedField("7601000152916", 0.9, 2),
                                "treatment.date_begin", new ExtractedField("26.08.2026", 0.8, 2)),
                        List.of(new ExtractedService("2026-08-26", "590", "1001", null, 1.0, 10.0, 10.0, 0.0, 0.9)),
                        List.of()));
        InvoiceExtractionService service = service(20);
        String jobId = service.submit("key", pdf(PDF), CONTEXT).jobId();

        scheduled.getFirst().run();

        ExtractionJobResponse job = service.find(jobId).orElseThrow();
        assertThat(job.status()).isEqualTo(ExtractionStatus.SUCCEEDED);
        assertThat(job.result().documentType()).isEqualTo("invoice");
        assertThat(job.result().fields()).containsOnlyKeys("biller.gln", "treatment.date_begin");
        assertThat(job.result().fields().get("biller.gln").page()).isEqualTo(2);
        assertThat(job.result().fields().get("treatment.date_begin").value()).isEqualTo("2026-08-26");
        assertThat(job.result().services()).hasSize(1);
        assertThat(job.error()).isNull();
    }

    @Test
    void process_pageFailure_isReportedAsWarning() throws IOException {
        when(pdfParser.renderPages(PDF)).thenReturn(List.of(new byte[]{1}, new byte[]{2}));
        when(extractor.extract(any(), eq(1), anyInt(), any(), any(), anyBoolean()))
                .thenThrow(new VisionInvoiceFieldExtractor.PageExtractionException("bad json", null));
        when(extractor.extract(any(), eq(2), anyInt(), any(), any(), anyBoolean()))
                .thenReturn(new PageExtraction(2, null, Map.of(), List.of(), List.of()));
        InvoiceExtractionService service = service(20);
        String jobId = service.submit("key", pdf(PDF), CONTEXT).jobId();

        scheduled.getFirst().run();

        ExtractionJobResponse job = service.find(jobId).orElseThrow();
        assertThat(job.status()).isEqualTo(ExtractionStatus.SUCCEEDED);
        assertThat(job.result().warnings()).containsExactly("Page 1 could not be analysed by the OCR service");
    }

    @Test
    void process_allPagesFail_marksJobFailed() throws IOException {
        when(pdfParser.renderPages(PDF)).thenReturn(List.of(new byte[]{1}));
        when(extractor.extract(any(), anyInt(), anyInt(), any(), any(), anyBoolean()))
                .thenThrow(new VisionInvoiceFieldExtractor.PageExtractionException("bad json", null));
        InvoiceExtractionService service = service(20);
        String jobId = service.submit("key", pdf(PDF), CONTEXT).jobId();

        scheduled.getFirst().run();

        ExtractionJobResponse job = service.find(jobId).orElseThrow();
        assertThat(job.status()).isEqualTo(ExtractionStatus.FAILED);
        assertThat(job.error().code()).isEqualTo(ExtractionError.EXTRACTION_FAILED);
        assertThat(job.result()).isNull();
    }

    @Test
    void process_unreadablePdf_marksJobFailed() throws IOException {
        when(pdfParser.renderPages(PDF)).thenThrow(new IOException("corrupted"));
        InvoiceExtractionService service = service(20);
        String jobId = service.submit("key", pdf(PDF), CONTEXT).jobId();

        scheduled.getFirst().run();

        ExtractionJobResponse job = service.find(jobId).orElseThrow();
        assertThat(job.status()).isEqualTo(ExtractionStatus.FAILED);
        assertThat(job.error().code()).isEqualTo(ExtractionError.UNREADABLE_DOCUMENT);
        verify(extractor, never()).extract(any(), anyInt(), anyInt(), any(), any(), anyBoolean());
    }

    @Test
    void process_tooManyPages_analysesOnlyMaxPages() throws IOException {
        when(pdfParser.renderPages(PDF)).thenReturn(List.of(new byte[]{1}, new byte[]{2}, new byte[]{3}));
        when(extractor.extract(any(), anyInt(), anyInt(), any(), any(), anyBoolean()))
                .thenReturn(new PageExtraction(1, null, Map.of(), List.of(), List.of()));
        InvoiceExtractionService service = service(2);
        String jobId = service.submit("key", pdf(PDF), CONTEXT).jobId();

        scheduled.getFirst().run();

        verify(extractor, times(2)).extract(any(), anyInt(), eq(3), any(), any(), anyBoolean());
        assertThat(service.find(jobId).orElseThrow().result().warnings())
                .containsExactly("Only the first 2 of 3 pages were analysed");
    }

    @Test
    void requestedFields_noMissingField_requestsEveryValueKey() {
        ExtractionContext context = new ExtractionContext(null, null, null, null, null);

        assertThat(InvoiceExtractionService.requestedFields(context)).isEqualTo(InvoiceFieldKeys.VALUE_KEYS);
    }

    @Test
    void hasPdfHeader_acceptsLeadingBytes() {
        byte[] withPrefix = "\n\n%PDF-1.4".getBytes(StandardCharsets.US_ASCII);

        assertThat(InvoiceExtractionService.hasPdfHeader(withPrefix)).isTrue();
        assertThat(InvoiceExtractionService.hasPdfHeader("%PD".getBytes(StandardCharsets.US_ASCII))).isFalse();
    }
}
