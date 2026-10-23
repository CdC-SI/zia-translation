package zas.admin.zia.translation.service.extraction;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;
import tools.jackson.databind.json.JsonMapper;
import zas.admin.zia.translation.service.InvalidDocumentException;
import zas.admin.zia.translation.service.parser.DocumentParser;
import zas.admin.zia.translation.service.parser.RenderedPages;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExtractionServiceTest {

    private static final byte[] PDF = "%PDF-1.7 content".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
    private static final ExtractionContext CONTEXT = new ExtractionContext("sumex-invoice", "D_0035", "de",
            Map.of(), List.of("biller.gln", "treatment.date_begin", "services", "not.a.key"), null);

    private static ExtractionRequestResolver resolver;
    private static ExtractionRequestResolver classifyingResolver;

    private ExtractionJobStore jobStore;
    private VisionFieldExtractor extractor;
    private PageClassifier classifier;
    private DocumentParser pdfParser;
    private DocumentParser imageParser;
    private final List<Runnable> scheduled = new ArrayList<>();
    private Executor executor = scheduled::add;

    @BeforeEach
    void setUp() throws IOException {
        if (resolver == null) {
            ExtractionProfileRegistry registry = new ExtractionProfileRegistry(JsonMapper.builder().build(), "");
            ExtractionProfile sumex = registry.find("sumex-invoice").orElseThrow();
            ExtractionProfileRegistry withoutClassification = mock(ExtractionProfileRegistry.class);
            when(withoutClassification.find("sumex-invoice")).thenReturn(Optional.of(new ExtractionProfile(
                    sumex.id(), sumex.description(), sumex.instructions(), sumex.fields(), sumex.collections())));
            resolver = new ExtractionRequestResolver(withoutClassification, 80, 2000);
            classifyingResolver = new ExtractionRequestResolver(registry, 80, 2000);
        }
        jobStore = new ExtractionJobStore();
        extractor = mock(VisionFieldExtractor.class);
        classifier = mock(PageClassifier.class);
        pdfParser = mock(DocumentParser.class);
        when(pdfParser.supportedMimeTypes()).thenReturn(List.of("application/pdf"));
        when(pdfParser.renderPages(any(), anyInt())).thenCallRealMethod();
        imageParser = mock(DocumentParser.class);
        when(imageParser.supportedMimeTypes()).thenReturn(List.of("image/png", "image/jpeg"));
        when(imageParser.renderPages(any(), anyInt())).thenCallRealMethod();
    }

    private ExtractionService service(int maxPages) {
        return new ExtractionService(jobStore, resolver, extractor, classifier, List.of(pdfParser, imageParser),
                executor, DataSize.ofKilobytes(1), maxPages, 4);
    }

    private ExtractionService classifyingService(int maxPages, int maxImagesPerCall) {
        return new ExtractionService(jobStore, classifyingResolver, extractor, classifier,
                List.of(pdfParser, imageParser), executor, DataSize.ofKilobytes(1), maxPages, maxImagesPerCall);
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
    void submit_duplicateDuringRejectedAdmission_waitsAndGetsAnAdmittedJob() throws Exception {
        CountDownLatch admitting = new CountDownLatch(1);
        CountDownLatch reject = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        executor = task -> {
            if (first.getAndSet(false)) {
                admitting.countDown();
                try {
                    reject.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new RejectedExecutionException("full");
            }
            scheduled.add(task);
        };
        ExtractionService service = service(20);
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<ExtractionJobResponse> duplicate = new AtomicReference<>();
        Thread submissionA = new Thread(() -> {
            try {
                service.submit("key", pdf(PDF), CONTEXT);
            } catch (Throwable t) {
                firstFailure.set(t);
            }
        });
        Thread submissionB = new Thread(() -> {
            try {
                duplicate.set(service.submit("key", pdf(PDF), CONTEXT));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });

        submissionA.start();
        assertThat(admitting.await(5, TimeUnit.SECONDS)).isTrue();
        submissionB.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (submissionB.getState() != Thread.State.BLOCKED && submissionB.getState() != Thread.State.TERMINATED
                && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(duplicate.get()).as("the duplicate waits for the admission of the first job").isNull();
        reject.countDown();
        submissionA.join(5000);
        submissionB.join(5000);

        assertThat(firstFailure.get()).isInstanceOf(ExtractionService.ExtractionCapacityExceededException.class);
        assertThat(duplicate.get()).isNotNull();
        assertThat(service.find(duplicate.get().jobId())).isPresent();
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
    void process_rendersOnlyTheAnalysedPages() throws IOException {
        doReturn(new RenderedPages(renderedPages(2), 50)).when(pdfParser).renderPages(PDF, 2);
        when(extractor.extract(any(), anyInt(), anyInt(), any()))
                .thenReturn(new PageExtraction(1, null, Map.of(), Map.of(), List.of()));
        ExtractionService service = service(2);
        String jobId = service.submit("key", pdf(PDF), CONTEXT).jobId();

        scheduled.getFirst().run();

        verify(pdfParser, never()).renderPages(any());
        verify(extractor, times(2)).extract(any(), anyInt(), eq(50), any());
        assertThat(service.find(jobId).orElseThrow().result().warnings())
                .contains("Only the first 2 of 50 pages were analysed");
    }

    // --- classified file (profile with page classification or page hints) ---

    private static final ExtractionContext IV_CONTEXT = new ExtractionContext("sumex-invoice", "D_0031", "de",
            Map.of(), List.of("balance.amount", "esr.iban", "services"), null);

    private static List<byte[]> renderedPages(int count) {
        List<byte[]> pages = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            pages.add(new byte[]{(byte) i});
        }
        return pages;
    }

    private static ClassifiedPage classified(int page, DocumentRole role, DocumentType type, String formId) {
        return new ClassifiedPage(new PageClassification(page, role, type, formId, 0.9, null), null, null, false);
    }

    private ExtractionJobResponse run(ExtractionService service, ExtractionContext context) throws IOException {
        String jobId = service.submit("key", pdf(PDF), context).jobId();
        scheduled.getFirst().run();
        return service.find(jobId).orElseThrow();
    }

    @Test
    void process_ivDossier_extractsOnlyTheMainDocument_inOneCall() throws IOException {
        when(pdfParser.renderPages(PDF)).thenReturn(renderedPages(4));
        when(classifier.classify(any(), anyInt(), any(), any())).thenAnswer(invocation -> {
            int page = invocation.<PageImage>getArgument(0).page();
            return page <= 2
                    ? classified(page, DocumentRole.MAIN, DocumentType.FORM, "300.001")
                    : classified(page, DocumentRole.SUPPORTING, DocumentType.INVOICE, null);
        });
        when(extractor.extract(any(), eq(4), any(), eq(true))).thenReturn(new PageExtraction(1, List.of(1, 2),
                "iv_invoice_form", Map.of("balance.amount", new ExtractedField("660.00", 0.9, 2, "Total in CHF 660.00")),
                Map.of("services", List.of(Map.of("code", new ExtractedField("909.04", 0.9, 1, "[x] 909.04")))),
                List.of("box of 909.05 left unclear")));

        ExtractionJobResponse job = run(classifyingService(20, 4), IV_CONTEXT);

        assertThat(job.status()).isEqualTo(ExtractionStatus.SUCCEEDED);
        assertThat(job.result().fields()).containsOnlyKeys("balance.amount");
        assertThat(job.result().collections().get("services")).hasSize(1);
        assertThat(job.result().pages()).extracting(PageClassification::documentRole).containsExactly(
                DocumentRole.MAIN, DocumentRole.MAIN, DocumentRole.SUPPORTING, DocumentRole.SUPPORTING);
        assertThat(job.result().warnings()).containsExactly("Pages 1, 2: box of 909.05 left unclear");
        verify(extractor).extract(argThat(images -> images.stream().map(PageImage::page).toList()
                .equals(List.of(1, 2))), eq(4), any(), eq(true));
        verify(extractor, never()).extract(any(byte[].class), anyInt(), anyInt(), any());
        verify(classifier).classify(argThat(image -> image.page() == 2), eq(4), any(),
                argThat(known -> known.size() == 1 && known.iterator().next().page() == 1));
    }

    @Test
    void process_pageHints_areNotClassifiedAgain() throws IOException {
        when(pdfParser.renderPages(PDF)).thenReturn(renderedPages(3));
        when(classifier.classify(any(), anyInt(), any(), any()))
                .thenReturn(classified(3, DocumentRole.OTHER, null, null));
        when(extractor.extract(any(), anyInt(), any(), anyBoolean()))
                .thenReturn(new PageExtraction(1, null, Map.of(), Map.of(), List.of()));
        ExtractionContext context = new ExtractionContext(null, "D_1", null, null, List.of("name"), null, List.of(
                new PageClassification(1, DocumentRole.MAIN, DocumentType.INVOICE, null, null, null),
                new PageClassification(2, DocumentRole.SUPPORTING, DocumentType.INVOICE, null, null, null),
                new PageClassification(9, DocumentRole.SUPPORTING, null, null, null, null)));

        ExtractionJobResponse job = run(classifyingService(20, 4), context);

        verify(classifier).classify(argThat(image -> image.page() == 3), eq(3), any(), any());
        verify(classifier, times(1)).classify(any(), anyInt(), any(), any());
        verify(extractor).extract(argThat(images -> images.size() == 1 && images.getFirst().page() == 1), eq(3),
                any(), eq(true));
        assertThat(job.result().pages()).extracting(PageClassification::page).containsExactly(1, 2, 3);
        assertThat(job.result().warnings()).contains("Hint of page 9 ignored: the page is not analysed");
    }

    @Test
    void process_mainDocumentLargerThanTheLimit_isSentInBatches() throws IOException {
        when(pdfParser.renderPages(PDF)).thenReturn(renderedPages(3));
        when(classifier.classify(any(), anyInt(), any(), any())).thenAnswer(invocation -> classified(
                invocation.<PageImage>getArgument(0).page(), DocumentRole.MAIN, DocumentType.INVOICE, null));
        when(extractor.extract(argThat(images -> images != null && images.size() == 2), anyInt(), any(),
                anyBoolean())).thenThrow(new VisionFieldExtractor.PageExtractionException("bad json", null));
        when(extractor.extract(argThat(images -> images != null && images.size() == 1), anyInt(), any(),
                anyBoolean())).thenReturn(new PageExtraction(3, null,
                Map.of("balance.amount", new ExtractedField("10.00", 0.9, 3, "10.00")), Map.of(), List.of()));

        ExtractionJobResponse job = run(classifyingService(20, 2), IV_CONTEXT);

        assertThat(job.status()).isEqualTo(ExtractionStatus.SUCCEEDED);
        assertThat(job.result().fields().get("balance.amount").page()).isEqualTo(3);
        assertThat(job.result().warnings()).contains("Pages 1, 2 could not be analysed by the extraction service");
    }

    @Test
    void process_classificationFailure_pageIsTreatedAsMainDocument() throws IOException {
        when(pdfParser.renderPages(PDF)).thenReturn(renderedPages(2));
        when(classifier.classify(argThat(image -> image != null && image.page() == 1), anyInt(), any(), any()))
                .thenReturn(classified(1, DocumentRole.MAIN, DocumentType.FORM, null));
        when(classifier.classify(argThat(image -> image != null && image.page() == 2), anyInt(), any(), any()))
                .thenThrow(new PageClassifier.PageClassificationException("down", null));
        when(extractor.extract(any(), anyInt(), any(), anyBoolean()))
                .thenReturn(new PageExtraction(1, null, Map.of(), Map.of(), List.of()));

        ExtractionJobResponse job = run(classifyingService(20, 4), IV_CONTEXT);

        verify(extractor).extract(argThat(images -> images.size() == 2), eq(2), any(), eq(true));
        assertThat(job.result().pages()).extracting(PageClassification::page).containsExactly(1);
        assertThat(job.result().warnings()).contains("Page 2 could not be classified");
    }

    @Test
    void process_extractionOfMainDocumentFails_marksJobFailed() throws IOException {
        when(pdfParser.renderPages(PDF)).thenReturn(renderedPages(1));
        when(classifier.classify(any(), anyInt(), any(), any()))
                .thenReturn(classified(1, DocumentRole.MAIN, DocumentType.FORM, null));
        when(extractor.extract(any(), anyInt(), any(), anyBoolean()))
                .thenThrow(new VisionFieldExtractor.PageExtractionException("bad json", null));

        ExtractionJobResponse job = run(classifyingService(20, 4), IV_CONTEXT);

        assertThat(job.status()).isEqualTo(ExtractionStatus.FAILED);
        assertThat(job.error().code()).isEqualTo(ExtractionError.EXTRACTION_FAILED);
    }

    @Test
    void process_profileWithoutClassification_doesNotClassify() throws IOException {
        when(pdfParser.renderPages(PDF)).thenReturn(renderedPages(1));
        when(extractor.extract(any(byte[].class), anyInt(), anyInt(), any()))
                .thenReturn(new PageExtraction(1, null, Map.of(), Map.of(), List.of()));

        ExtractionJobResponse job = run(service(20), CONTEXT);

        assertThat(job.result().pages()).isNull();
        verifyNoInteractions(classifier);
    }

    @Test
    void constructor_rejectsInvalidImageLimit() {
        assertThatThrownBy(() -> classifyingService(20, 0)).isInstanceOf(IllegalArgumentException.class);
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
