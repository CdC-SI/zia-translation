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

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
        when(translationService.submitPdfTranslation(any(), eq("de")))
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
    void getJobStatus_knownJob_returnsStatus() throws Exception {
        when(translationService.getJobStatusResponse("job-123"))
                .thenReturn(Optional.of(new TranslationJobResponse("job-123", JobStatus.PROCESSING)));

        mockMvc.perform(get("/api/translation/pdf/job-123/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value("job-123"))
                .andExpect(jsonPath("$.status").value("PROCESSING"));
    }

    @Test
    void getJobStatus_unknownJob_returns404() throws Exception {
        when(translationService.getJobStatusResponse("missing")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/translation/pdf/missing/status"))
                .andExpect(status().isNotFound());
    }

    @Test
    void downloadPdf_processingJob_returns409() throws Exception {
        when(translationService.getJobStatus("job-1")).thenReturn(Optional.of(JobStatus.PROCESSING));

        mockMvc.perform(get("/api/translation/pdf/job-1"))
                .andExpect(status().isConflict());
    }

    @Test
    void downloadPdf_completedJob_returnsPdf() throws Exception {
        byte[] pdfBytes = "%PDF-1.4".getBytes();
        when(translationService.getJobStatus("job-2")).thenReturn(Optional.of(JobStatus.COMPLETED));
        when(translationService.getTranslatedPdf("job-2")).thenReturn(Optional.of(new ByteArrayResource(pdfBytes)));

        mockMvc.perform(get("/api/translation/pdf/job-2"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"job-2.pdf\""));
    }

    @Test
    void downloadPdf_completedButMissingFile_returns410() throws Exception {
        when(translationService.getJobStatus("job-3")).thenReturn(Optional.of(JobStatus.COMPLETED));
        when(translationService.getTranslatedPdf("job-3")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/translation/pdf/job-3"))
                .andExpect(status().isGone());
    }

    @Test
    void downloadPdf_failedJob_returns422() throws Exception {
        when(translationService.getJobStatus("job-4")).thenReturn(Optional.of(JobStatus.FAILED));

        mockMvc.perform(get("/api/translation/pdf/job-4"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void translateToText_streamsPageAndCompleteEvents() throws Exception {
        when(translationService.translateToTextStream(any(), eq("fr")))
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
        when(translationService.translateToTextStream(any(), anyString()))
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
        when(translationService.submitPdfTranslation(any(), anyString()))
                .thenThrow(new InvalidDocumentException("Unsupported file format."));

        MockMultipartFile file = new MockMultipartFile("file", "test.txt", "text/plain", "not a pdf".getBytes());

        mockMvc.perform(multipart("/api/translation/pdf")
                        .file(file)
                        .param("targetLanguage", "fr"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }
}
