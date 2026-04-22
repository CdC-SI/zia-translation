package zas.admin.zia.translation.service.controller;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import zas.admin.zia.translation.service.InvalidDocumentException;
import zas.admin.zia.translation.service.TranslationProcessingException;
import zas.admin.zia.translation.service.TranslationService;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {TranslationController.class, GlobalExceptionHandler.class})
class TranslationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TranslationService translationService;

    // --- /api/translation/text ---

    @Test
    void translateToText_validRequest_returnsTranslatedPages() throws Exception {
        when(translationService.translateToText(any(), eq("fr")))
                .thenReturn(List.of("Page 1 traduit", "Page 2 traduit"));

        MockMultipartFile file = new MockMultipartFile(
                "file", "test.pdf", "application/pdf", createMinimalPdf());

        mockMvc.perform(multipart("/api/translation/text")
                        .file(file)
                        .param("targetLanguage", "fr"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.pages[0]").value("Page 1 traduit"))
                .andExpect(jsonPath("$.pages[1]").value("Page 2 traduit"));
    }

    @Test
    void translateToText_invalidDocument_returns400() throws Exception {
        when(translationService.translateToText(any(), any()))
                .thenThrow(new InvalidDocumentException("File is missing or empty."));

        MockMultipartFile file = new MockMultipartFile(
                "file", "empty.pdf", "application/pdf", new byte[0]);

        mockMvc.perform(multipart("/api/translation/text")
                        .file(file)
                        .param("targetLanguage", "fr"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("File is missing or empty."));
    }

    @Test
    void translateToText_processingFailure_returns422() throws Exception {
        when(translationService.translateToText(any(), any()))
                .thenThrow(new TranslationProcessingException("OCR failed", new RuntimeException()));

        MockMultipartFile file = new MockMultipartFile(
                "file", "test.pdf", "application/pdf", createMinimalPdf());

        mockMvc.perform(multipart("/api/translation/text")
                        .file(file)
                        .param("targetLanguage", "en"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422));
    }

    // --- /api/translation/pdf ---

    @Test
    void translateToPdf_validRequest_returnsPdfBytes() throws Exception {
        byte[] pdfBytes = createMinimalPdf();
        when(translationService.translateToPdf(any(), eq("de")))
                .thenReturn(pdfBytes);

        MockMultipartFile file = new MockMultipartFile(
                "file", "test.pdf", "application/pdf", createMinimalPdf());

        mockMvc.perform(multipart("/api/translation/pdf")
                        .file(file)
                        .param("targetLanguage", "de"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF));
    }

    @Test
    void translateToPdf_invalidDocument_returns400() throws Exception {
        when(translationService.translateToPdf(any(), any()))
                .thenThrow(new InvalidDocumentException("Unsupported file format."));

        MockMultipartFile file = new MockMultipartFile(
                "file", "test.txt", "text/plain", "not a pdf".getBytes());

        mockMvc.perform(multipart("/api/translation/pdf")
                        .file(file)
                        .param("targetLanguage", "fr"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void translateToText_missingTargetLanguage_returns400() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.pdf", "application/pdf", createMinimalPdf());

        mockMvc.perform(multipart("/api/translation/text")
                        .file(file))
                .andExpect(status().isBadRequest());
    }

    private static byte[] createMinimalPdf() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.beginText();
                cs.newLineAtOffset(50, 700);
                cs.showText("Test document");
                cs.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }
}
