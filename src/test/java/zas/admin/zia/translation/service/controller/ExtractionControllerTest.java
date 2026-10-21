package zas.admin.zia.translation.service.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import zas.admin.zia.translation.service.InvalidDocumentException;
import zas.admin.zia.translation.service.extraction.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {ExtractionController.class, ExtractionExceptionHandler.class})
@AutoConfigureMockMvc(addFilters = false)
class ExtractionControllerTest {

    private static final String JOB_ID = "6f1c2e0a-8d4b-4a51-9a57-2c1d3b9e7f10";
    private static final Instant SUBMITTED_AT = Instant.parse("2026-10-20T14:38:03Z");
    private static final String CONTEXT_JSON = """
            {"profile": "sumex-invoice", "documentId": "D_0035", "language": "de",
             "knownFields": {"patient.ssn": "7568250251199"},
             "missingFields": ["biller.gln", "services"],
             "instructions": "The biller is the hearing aid acoustician.",
             "unknownProperty": true}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ExtractionService extractionService;

    @Test
    void submit_returns202WithLocationAndRetryAfter() throws Exception {
        when(extractionService.submit(eq("D_0035-3f2a9c1be04d7a61"), any(), argThat(context ->
                "sumex-invoice".equals(context.profile())
                        && "D_0035".equals(context.documentId())
                        && context.missingFields().equals(List.of("biller.gln", "services"))
                        && "7568250251199".equals(context.knownFields().get("patient.ssn"))
                        && context.instructions().startsWith("The biller"))))
                .thenReturn(new ExtractionJobResponse(JOB_ID, ExtractionStatus.PENDING, SUBMITTED_AT, null, null));

        mockMvc.perform(submitRequest().header("Idempotency-Key", "D_0035-3f2a9c1be04d7a61"))
                .andExpect(status().isAccepted())
                .andExpect(header().string(HttpHeaders.LOCATION, "extractions/" + JOB_ID))
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "5"))
                .andExpect(jsonPath("$.jobId").value(JOB_ID))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.submittedAt").value("2026-10-20T14:38:03Z"))
                .andExpect(jsonPath("$.result").doesNotExist())
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    void submit_missingIdempotencyKey_returns400() throws Exception {
        mockMvc.perform(submitRequest())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Missing required header: Idempotency-Key"));

        verifyNoInteractions(extractionService);
    }

    @Test
    void submit_missingFile_returns400() throws Exception {
        mockMvc.perform(multipart(ExtractionController.BASE_PATH)
                        .file(contextPart(CONTEXT_JSON))
                        .header("Idempotency-Key", "key"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(extractionService);
    }

    @Test
    void submit_malformedContext_returns400() throws Exception {
        mockMvc.perform(multipart(ExtractionController.BASE_PATH)
                        .file(filePart())
                        .file(contextPart("{not json"))
                        .header("Idempotency-Key", "key"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(extractionService);
    }

    @Test
    void submit_invalidRequest_returns400() throws Exception {
        when(extractionService.submit(any(), any(), any())).thenThrow(new InvalidDocumentException("Unknown extraction profile."));

        mockMvc.perform(submitRequest().header("Idempotency-Key", "key"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Unknown extraction profile."));
    }

    @Test
    void submit_documentTooLarge_returns413() throws Exception {
        when(extractionService.submit(any(), any(), any()))
                .thenThrow(new ExtractionService.DocumentTooLargeException("too large"));

        mockMvc.perform(submitRequest().header("Idempotency-Key", "key"))
                .andExpect(status().isContentTooLarge());
    }

    @Test
    void submit_noCapacity_returns429() throws Exception {
        when(extractionService.submit(any(), any(), any()))
                .thenThrow(new ExtractionService.ExtractionCapacityExceededException("busy", null));

        mockMvc.perform(submitRequest().header("Idempotency-Key", "key"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
    }

    @Test
    void get_runningJob_returns200WithRetryAfter() throws Exception {
        when(extractionService.find(JOB_ID)).thenReturn(Optional.of(
                new ExtractionJobResponse(JOB_ID, ExtractionStatus.RUNNING, SUBMITTED_AT, null, null)));

        mockMvc.perform(get(ExtractionController.BASE_PATH + "/" + JOB_ID))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "5"))
                .andExpect(jsonPath("$.status").value("RUNNING"));
    }

    @Test
    void get_succeededJob_returnsResultWithoutRetryAfter() throws Exception {
        ExtractionResponse result = new ExtractionResponse("sumex-invoice", "medical_invoice",
                Map.of("biller.gln", new ExtractedField("7601000152916", 0.93, 1, "GLN 7601000152916")),
                Map.of("services", List.of(Map.of("amount", new ExtractedField("100.00", 0.8, 2, "100.00")))),
                List.of("Page 2: stamp over the total"));
        when(extractionService.find(JOB_ID)).thenReturn(Optional.of(
                new ExtractionJobResponse(JOB_ID, ExtractionStatus.SUCCEEDED, SUBMITTED_AT, result, null)));

        mockMvc.perform(get(ExtractionController.BASE_PATH + "/" + JOB_ID))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.result.profile").value("sumex-invoice"))
                .andExpect(jsonPath("$.result.documentType").value("medical_invoice"))
                .andExpect(jsonPath("$.result.fields['biller.gln'].value").value("7601000152916"))
                .andExpect(jsonPath("$.result.fields['biller.gln'].confidence").value(0.93))
                .andExpect(jsonPath("$.result.fields['biller.gln'].page").value(1))
                .andExpect(jsonPath("$.result.fields['biller.gln'].evidence").value("GLN 7601000152916"))
                .andExpect(jsonPath("$.result.collections.services[0].amount.value").value("100.00"))
                .andExpect(jsonPath("$.result.warnings[0]").value("Page 2: stamp over the total"))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    void get_failedJob_returnsError() throws Exception {
        when(extractionService.find(JOB_ID)).thenReturn(Optional.of(new ExtractionJobResponse(JOB_ID,
                ExtractionStatus.FAILED, SUBMITTED_AT, null,
                new ExtractionError(ExtractionError.UNREADABLE_DOCUMENT, "The document cannot be read."))));

        mockMvc.perform(get(ExtractionController.BASE_PATH + "/" + JOB_ID))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.error.code").value("UNREADABLE_DOCUMENT"))
                .andExpect(jsonPath("$.result").doesNotExist());
    }

    @Test
    void get_unknownJob_returns404() throws Exception {
        when(extractionService.find("unknown")).thenReturn(Optional.empty());

        mockMvc.perform(get(ExtractionController.BASE_PATH + "/unknown"))
                .andExpect(status().isNotFound());
    }

    private static MockMultipartHttpServletRequestBuilder submitRequest() {
        return multipart(ExtractionController.BASE_PATH)
                .file(filePart())
                .file(contextPart(CONTEXT_JSON));
    }

    private static MockMultipartFile filePart() {
        return new MockMultipartFile("file", "invoice.pdf", MediaType.APPLICATION_PDF_VALUE, "%PDF-1.7".getBytes());
    }

    private static MockMultipartFile contextPart(String json) {
        return new MockMultipartFile("context", "", MediaType.APPLICATION_JSON_VALUE, json.getBytes());
    }
}
