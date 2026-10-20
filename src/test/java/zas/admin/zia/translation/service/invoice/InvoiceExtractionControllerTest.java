package zas.admin.zia.translation.service.invoice;

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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {InvoiceExtractionController.class, InvoiceExtractionExceptionHandler.class})
@AutoConfigureMockMvc(addFilters = false)
class InvoiceExtractionControllerTest {

    private static final String JOB_ID = "6f1c2e0a-8d4b-4a51-9a57-2c1d3b9e7f10";
    private static final Instant SUBMITTED_AT = Instant.parse("2026-10-20T14:38:03Z");
    private static final String CONTEXT_JSON = """
            {"documentId": "D_0035", "language": "de", "officeAI": "301",
             "knownFields": {"patient.ssn": "7568250251199"},
             "missingFields": ["biller.gln", "services"],
             "unknownProperty": true}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InvoiceExtractionService extractionService;

    @Test
    void submit_returns202WithLocationAndRetryAfter() throws Exception {
        when(extractionService.submit(eq("D_0035-3f2a9c1be04d7a61"), any(), argThat(context ->
                "D_0035".equals(context.documentId())
                        && context.missingFields().equals(List.of("biller.gln", "services"))
                        && "7568250251199".equals(context.knownFields().get("patient.ssn")))))
                .thenReturn(new ExtractionJobResponse(JOB_ID, ExtractionStatus.PENDING, SUBMITTED_AT, null, null));

        mockMvc.perform(submitRequest().header("Idempotency-Key", "D_0035-3f2a9c1be04d7a61"))
                .andExpect(status().isAccepted())
                .andExpect(header().string(HttpHeaders.LOCATION, "invoice-extractions/" + JOB_ID))
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
        mockMvc.perform(multipart(InvoiceExtractionController.BASE_PATH)
                        .file(contextPart(CONTEXT_JSON))
                        .header("Idempotency-Key", "key"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(extractionService);
    }

    @Test
    void submit_malformedContext_returns400() throws Exception {
        mockMvc.perform(multipart(InvoiceExtractionController.BASE_PATH)
                        .file(filePart())
                        .file(contextPart("{not json"))
                        .header("Idempotency-Key", "key"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(extractionService);
    }

    @Test
    void submit_invalidDocument_returns400() throws Exception {
        when(extractionService.submit(any(), any(), any())).thenThrow(new InvalidDocumentException("The document is not a PDF."));

        mockMvc.perform(submitRequest().header("Idempotency-Key", "key"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("The document is not a PDF."));
    }

    @Test
    void submit_documentTooLarge_returns413() throws Exception {
        when(extractionService.submit(any(), any(), any()))
                .thenThrow(new InvoiceExtractionService.DocumentTooLargeException("too large"));

        mockMvc.perform(submitRequest().header("Idempotency-Key", "key"))
                .andExpect(status().isContentTooLarge());
    }

    @Test
    void submit_noCapacity_returns429() throws Exception {
        when(extractionService.submit(any(), any(), any()))
                .thenThrow(new InvoiceExtractionService.ExtractionCapacityExceededException("busy", null));

        mockMvc.perform(submitRequest().header("Idempotency-Key", "key"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
    }

    @Test
    void get_runningJob_returns200WithRetryAfter() throws Exception {
        when(extractionService.find(JOB_ID)).thenReturn(Optional.of(
                new ExtractionJobResponse(JOB_ID, ExtractionStatus.RUNNING, SUBMITTED_AT, null, null)));

        mockMvc.perform(get(InvoiceExtractionController.BASE_PATH + "/" + JOB_ID))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "5"))
                .andExpect(jsonPath("$.status").value("RUNNING"));
    }

    @Test
    void get_succeededJob_returnsResultWithoutRetryAfter() throws Exception {
        ExtractionResponse result = new ExtractionResponse("hearing_aid_invoice",
                Map.of("biller.gln", new ExtractedField("7601000152916", 0.93, 1)),
                List.of(new ExtractedService("2026-08-26", "590", "1001", "Fitting", 1.0, 100.0, 100.0, 8.1, 0.8)),
                List.of("Page 2: stamp over the total"));
        when(extractionService.find(JOB_ID)).thenReturn(Optional.of(
                new ExtractionJobResponse(JOB_ID, ExtractionStatus.SUCCEEDED, SUBMITTED_AT, result, null)));

        mockMvc.perform(get(InvoiceExtractionController.BASE_PATH + "/" + JOB_ID))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.result.documentType").value("hearing_aid_invoice"))
                .andExpect(jsonPath("$.result.fields['biller.gln'].value").value("7601000152916"))
                .andExpect(jsonPath("$.result.fields['biller.gln'].confidence").value(0.93))
                .andExpect(jsonPath("$.result.fields['biller.gln'].page").value(1))
                .andExpect(jsonPath("$.result.services[0].tariffType").value("590"))
                .andExpect(jsonPath("$.result.services[0].amount").value(100.0))
                .andExpect(jsonPath("$.result.warnings[0]").value("Page 2: stamp over the total"))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    void get_failedJob_returnsError() throws Exception {
        when(extractionService.find(JOB_ID)).thenReturn(Optional.of(new ExtractionJobResponse(JOB_ID,
                ExtractionStatus.FAILED, SUBMITTED_AT, null,
                new ExtractionError(ExtractionError.UNREADABLE_DOCUMENT, "The PDF document cannot be read."))));

        mockMvc.perform(get(InvoiceExtractionController.BASE_PATH + "/" + JOB_ID))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.error.code").value("UNREADABLE_DOCUMENT"))
                .andExpect(jsonPath("$.result").doesNotExist());
    }

    @Test
    void get_unknownJob_returns404() throws Exception {
        when(extractionService.find("unknown")).thenReturn(Optional.empty());

        mockMvc.perform(get(InvoiceExtractionController.BASE_PATH + "/unknown"))
                .andExpect(status().isNotFound());
    }

    private static MockMultipartHttpServletRequestBuilder submitRequest() {
        return multipart(InvoiceExtractionController.BASE_PATH)
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
