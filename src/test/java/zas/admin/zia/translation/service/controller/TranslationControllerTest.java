package zas.admin.zia.translation.service.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import reactor.core.publisher.Flux;
import zas.admin.zia.translation.service.InvalidDocumentException;
import zas.admin.zia.translation.service.TranslationService;
import zas.admin.zia.translation.service.dto.TranslationJobResponse;
import zas.admin.zia.translation.service.dto.TranslationStreamEvent;
import zas.admin.zia.translation.service.job.JobStatus;

import zas.admin.zia.translation.service.TranslationStrategy;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {TranslationController.class, GlobalExceptionHandler.class})
@AutoConfigureMockMvc(addFilters = false)
class TranslationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TranslationService translationService;

    @Test
    void translateToPdf_validRequest_returns202AndJobId() throws Exception {
        when(translationService.submitPdfTranslation(any(), eq("de"), isNull()))
                .thenReturn(new TranslationJobResponse("job-123", JobStatus.PENDING));

        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", "%PDF".getBytes());

        mockMvc.perform(multipart("/api/translation/pdf")
                        .file(file)
                        .param("targetLanguage", "de"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value("job-123"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void translateToMarkdown_validRequest_returns202AndJobId() throws Exception {
        when(translationService.submitMarkdownTranslation(any(), eq("de"), isNull()))
                .thenReturn(new TranslationJobResponse("job-md-1", JobStatus.PENDING));

        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", "%PDF".getBytes());

        mockMvc.perform(multipart("/api/translation/md")
                        .file(file)
                        .param("targetLanguage", "de"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value("job-md-1"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void translateToMarkdown_invalidDocument_returns400() throws Exception {
        when(translationService.submitMarkdownTranslation(any(), anyString(), isNull()))
                .thenThrow(new InvalidDocumentException("Unsupported file format."));

        MockMultipartFile file = new MockMultipartFile("file", "test.txt", "text/plain", "not a pdf".getBytes());

        mockMvc.perform(multipart("/api/translation/md")
                        .file(file)
                        .param("targetLanguage", "fr"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void getJobStatus_knownJob_returnsStatus() throws Exception {
        when(translationService.getJobStatusResponse("job-123"))
                .thenReturn(Optional.of(new TranslationJobResponse("job-123", JobStatus.PROCESSING)));

        mockMvc.perform(get("/api/translation/jobs/job-123/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value("job-123"))
                .andExpect(jsonPath("$.status").value("PROCESSING"));
    }

    @Test
    void getJobStatus_unknownJob_returns404() throws Exception {
        when(translationService.getJobStatusResponse("missing")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/translation/jobs/missing/status"))
                .andExpect(status().isNotFound());
    }

    @Test
    void downloadJob_processingJob_returns409() throws Exception {
        when(translationService.getJobStatus("job-1")).thenReturn(Optional.of(JobStatus.PROCESSING));

        mockMvc.perform(get("/api/translation/jobs/job-1"))
                .andExpect(status().isConflict());
    }

    @Test
    void downloadJob_completedPdfJob_returnsPdf() throws Exception {
        byte[] pdfBytes = "%PDF-1.4".getBytes();
        when(translationService.getJobStatus("job-2")).thenReturn(Optional.of(JobStatus.COMPLETED));
        when(translationService.getTranslatedFile("job-2")).thenReturn(Optional.of(
                new TranslationService.TranslatedFile(new ByteArrayResource(pdfBytes), MediaType.APPLICATION_PDF, "job-2.pdf")));

        mockMvc.perform(get("/api/translation/jobs/job-2"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"job-2.pdf\""));
    }

    @Test
    void downloadJob_completedMarkdownJob_returnsMarkdown() throws Exception {
        byte[] markdownBytes = "# Titre".getBytes();
        MediaType markdownMediaType = new MediaType("text", "markdown");
        when(translationService.getJobStatus("job-md-2")).thenReturn(Optional.of(JobStatus.COMPLETED));
        when(translationService.getTranslatedFile("job-md-2")).thenReturn(Optional.of(
                new TranslationService.TranslatedFile(new ByteArrayResource(markdownBytes), markdownMediaType, "job-md-2.md")));

        mockMvc.perform(get("/api/translation/jobs/job-md-2"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(markdownMediaType))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"job-md-2.md\""));
    }

    @Test
    void downloadJob_completedButMissingFile_returns410() throws Exception {
        when(translationService.getJobStatus("job-3")).thenReturn(Optional.of(JobStatus.COMPLETED));
        when(translationService.getTranslatedFile("job-3")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/translation/jobs/job-3"))
                .andExpect(status().isGone());
    }

    @Test
    void downloadJob_failedJob_returns422() throws Exception {
        when(translationService.getJobStatus("job-4")).thenReturn(Optional.of(JobStatus.FAILED));

        mockMvc.perform(get("/api/translation/jobs/job-4"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void downloadJob_unknownJob_returns404() throws Exception {
        when(translationService.getJobStatus("missing")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/translation/jobs/missing"))
                .andExpect(status().isNotFound());
    }

    // --- deprecated /pdf/{jobId}... endpoints: non-regression ---

    @Test
    void deprecatedGetJobStatus_knownJob_returnsStatus() throws Exception {
        when(translationService.getJobStatusResponse("job-123"))
                .thenReturn(Optional.of(new TranslationJobResponse("job-123", JobStatus.PROCESSING)));

        mockMvc.perform(get("/api/translation/pdf/job-123/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value("job-123"))
                .andExpect(jsonPath("$.status").value("PROCESSING"));
    }

    @Test
    void deprecatedGetJobStatus_unknownJob_returns404() throws Exception {
        when(translationService.getJobStatusResponse("missing")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/translation/pdf/missing/status"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deprecatedDownloadPdf_processingJob_returns409() throws Exception {
        when(translationService.getJobStatus("job-1")).thenReturn(Optional.of(JobStatus.PROCESSING));

        mockMvc.perform(get("/api/translation/pdf/job-1"))
                .andExpect(status().isConflict());
    }

    @Test
    void deprecatedDownloadPdf_completedJob_returnsPdf() throws Exception {
        byte[] pdfBytes = "%PDF-1.4".getBytes();
        when(translationService.getJobStatus("job-2")).thenReturn(Optional.of(JobStatus.COMPLETED));
        when(translationService.getTranslatedFile("job-2")).thenReturn(Optional.of(
                new TranslationService.TranslatedFile(new ByteArrayResource(pdfBytes), MediaType.APPLICATION_PDF, "job-2.pdf")));

        mockMvc.perform(get("/api/translation/pdf/job-2"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"job-2.pdf\""));
    }

    @Test
    void deprecatedDownloadPdf_completedButMissingFile_returns410() throws Exception {
        when(translationService.getJobStatus("job-3")).thenReturn(Optional.of(JobStatus.COMPLETED));
        when(translationService.getTranslatedFile("job-3")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/translation/pdf/job-3"))
                .andExpect(status().isGone());
    }

    @Test
    void deprecatedDownloadPdf_failedJob_returns422() throws Exception {
        when(translationService.getJobStatus("job-4")).thenReturn(Optional.of(JobStatus.FAILED));

        mockMvc.perform(get("/api/translation/pdf/job-4"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void translateToText_streamsPageAndCompleteEvents() throws Exception {
        when(translationService.translateToTextStream(any(), eq("fr"), isNull()))
                .thenReturn(Flux.just(
                        new TranslationStreamEvent.Token(1, "Page 1"),
                        new TranslationStreamEvent.Token(1, " traduit"),
                        new TranslationStreamEvent.PageComplete(1, "Page 1 traduit"),
                        new TranslationStreamEvent.Token(2, "Page 2 traduit"),
                        new TranslationStreamEvent.PageComplete(2, "Page 2 traduit")
                ));

        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", "%PDF".getBytes());

        MvcResult mvcResult = mockMvc.perform(multipart("/api/translation/text")
                        .file(file)
                        .param("targetLanguage", "fr"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:token")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:page")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"pageNumber\":1")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:complete")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"totalPages\":2")));
    }

    @Test
    void translateToText_streamingFailure_returnsErrorEvent() throws Exception {
        when(translationService.translateToTextStream(any(), anyString(), isNull()))
                .thenReturn(Flux.error(new RuntimeException("OCR extraction failed on page 3")));

        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", "%PDF".getBytes());

        MvcResult mvcResult = mockMvc.perform(multipart("/api/translation/text")
                        .file(file)
                        .param("targetLanguage", "fr"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:error")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Unexpected error during streaming.")));
    }

    @Test
    void translateToText_missingTargetLanguage_returns400() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", "%PDF".getBytes());

        mockMvc.perform(multipart("/api/translation/text")
                        .file(file))
                .andExpect(status().isBadRequest());
    }

    @Test
    void translateToPdf_invalidDocument_returns400() throws Exception {
        when(translationService.submitPdfTranslation(any(), anyString(), isNull()))
                .thenThrow(new InvalidDocumentException("Unsupported file format."));

        MockMultipartFile file = new MockMultipartFile("file", "test.txt", "text/plain", "not a pdf".getBytes());

        mockMvc.perform(multipart("/api/translation/pdf")
                        .file(file)
                        .param("targetLanguage", "fr"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    // --- strategy parameter tests ---

    @Test
    void translateToPdf_withStrategySingle_passesStrategyToService() throws Exception {
        when(translationService.submitPdfTranslation(any(), eq("de"), eq(TranslationStrategy.SINGLE)))
                .thenReturn(new TranslationJobResponse("job-s1", JobStatus.PENDING));

        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", "%PDF".getBytes());

        mockMvc.perform(multipart("/api/translation/pdf")
                        .file(file)
                        .param("targetLanguage", "de")
                        .param("strategy", "single"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value("job-s1"));
    }

    @Test
    void translateToPdf_withStrategyDual_passesStrategyToService() throws Exception {
        when(translationService.submitPdfTranslation(any(), eq("de"), eq(TranslationStrategy.DUAL)))
                .thenReturn(new TranslationJobResponse("job-d1", JobStatus.PENDING));

        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", "%PDF".getBytes());

        mockMvc.perform(multipart("/api/translation/pdf")
                        .file(file)
                        .param("targetLanguage", "de")
                        .param("strategy", "dual"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value("job-d1"));
    }

    @Test
    void translateToPdf_withInvalidStrategy_returns400() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", "%PDF".getBytes());

        mockMvc.perform(multipart("/api/translation/pdf")
                        .file(file)
                        .param("targetLanguage", "de")
                        .param("strategy", "fast"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(
                        "Invalid value 'fast' for parameter 'strategy'. Allowed values: single, dual"));
    }

    @Test
    void translateToMarkdown_withStrategySingle_passesStrategyToService() throws Exception {
        when(translationService.submitMarkdownTranslation(any(), eq("fr"), eq(TranslationStrategy.SINGLE)))
                .thenReturn(new TranslationJobResponse("job-md-s1", JobStatus.PENDING));

        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", "%PDF".getBytes());

        mockMvc.perform(multipart("/api/translation/md")
                        .file(file)
                        .param("targetLanguage", "fr")
                        .param("strategy", "single"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value("job-md-s1"));
    }

    @Test
    void translateToMarkdown_withInvalidStrategy_returns400() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", "%PDF".getBytes());

        mockMvc.perform(multipart("/api/translation/md")
                        .file(file)
                        .param("targetLanguage", "fr")
                        .param("strategy", "auto"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(
                        "Invalid value 'auto' for parameter 'strategy'. Allowed values: single, dual"));
    }

    @Test
    void translateToText_withStrategyDual_passesStrategyToService() throws Exception {
        when(translationService.translateToTextStream(any(), eq("fr"), eq(TranslationStrategy.DUAL)))
                .thenReturn(Flux.just(
                        new TranslationStreamEvent.PageComplete(1, "Page 1")
                ));

        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", "%PDF".getBytes());

        MvcResult mvcResult = mockMvc.perform(multipart("/api/translation/text")
                        .file(file)
                        .param("targetLanguage", "fr")
                        .param("strategy", "dual"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk());
    }

    @Test
    void translateToText_withInvalidStrategy_returns400() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", "%PDF".getBytes());

        mockMvc.perform(multipart("/api/translation/text")
                        .file(file)
                        .param("targetLanguage", "fr")
                        .param("strategy", "unknown"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(
                        "Invalid value 'unknown' for parameter 'strategy'. Allowed values: single, dual"));
    }
}
