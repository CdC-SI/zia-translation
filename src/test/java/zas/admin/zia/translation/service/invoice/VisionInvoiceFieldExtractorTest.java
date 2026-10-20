package zas.admin.zia.translation.service.invoice;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;
import tools.jackson.databind.json.JsonMapper;
import zas.admin.zia.translation.service.image.VisionImagePreprocessor;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VisionInvoiceFieldExtractorTest {

    private static final ExtractionContext CONTEXT = new ExtractionContext("D_0035", "de", "301",
            Map.of("patient.familyname", "Güntensperger"), List.of("biller.gln", "services"));

    private ChatClient visionClient;
    private ChatClient.ChatClientRequestSpec requestSpec;
    private ChatClient.CallResponseSpec callResponseSpec;
    private VisionInvoiceFieldExtractor extractor;

    @BeforeEach
    void setUp() {
        visionClient = mock(ChatClient.class);
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        callResponseSpec = mock(ChatClient.CallResponseSpec.class);
        when(visionClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.messages(any(Message.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);

        VisionImagePreprocessor preprocessor = mock(VisionImagePreprocessor.class);
        when(preprocessor.toMedia(any(byte[].class)))
                .thenReturn(new Media(MimeTypeUtils.IMAGE_JPEG, new ByteArrayResource(new byte[]{1})));

        extractor = new VisionInvoiceFieldExtractor(visionClient, preprocessor, JsonMapper.builder().build());
    }

    @Test
    void extract_parsesFieldsServicesAndWarnings() {
        when(callResponseSpec.content()).thenReturn("""
                ```json
                {"documentType": "hearing_aid_invoice",
                 "fields": {"biller.gln": {"value": "7601000152916", "confidence": 0.93},
                            "treatment.date_begin": {"value": "2026-08-26"},
                            "balance.amount": {"value": 1234.5, "confidence": 0.8},
                            "biller.zsr": {"value": null, "confidence": 0.2}},
                 "services": [{"date": "2026-08-26", "tariffType": "590", "code": "1001", "quantity": "2",
                               "amount": 200.0, "confidence": 0.7, "extra": "ignored"}],
                 "warnings": ["stamp over the total", ""]}
                ```
                """);

        PageExtraction page = extractor.extract(new byte[]{1}, 2, 3, CONTEXT, Set.of("biller.gln"), true);

        assertThat(page.page()).isEqualTo(2);
        assertThat(page.documentType()).isEqualTo("hearing_aid_invoice");
        assertThat(page.fields()).containsOnlyKeys("biller.gln", "treatment.date_begin", "balance.amount");
        assertThat(page.fields().get("biller.gln")).isEqualTo(new ExtractedField("7601000152916", 0.93, 2));
        assertThat(page.fields().get("treatment.date_begin").confidence()).isNull();
        assertThat(page.fields().get("balance.amount").value()).isEqualTo("1234.5");
        assertThat(page.services()).singleElement().satisfies(service -> {
            assertThat(service.code()).isEqualTo("1001");
            assertThat(service.quantity()).isEqualTo(2.0);
            assertThat(service.amount()).isEqualTo(200.0);
        });
        assertThat(page.warnings()).containsExactly("stamp over the total");
    }

    @Test
    void extract_sendsSystemPromptAndPageImage() {
        when(callResponseSpec.content()).thenReturn("{\"fields\": {}}");

        extractor.extract(new byte[]{1}, 1, 1, CONTEXT, Set.of("biller.gln"), true);

        verify(requestSpec).system(VisionInvoiceFieldExtractor.SYSTEM_PROMPT);
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(requestSpec).messages(captor.capture());
        UserMessage message = (UserMessage) captor.getValue();
        assertThat(message.getMedia()).hasSize(1);
        assertThat(message.getText()).contains("- biller.gln", "patient.familyname = Güntensperger");
    }

    @Test
    void extract_invalidJson_throws() {
        when(callResponseSpec.content()).thenReturn("I cannot read this page.");

        assertThatThrownBy(() -> extractor.extract(new byte[]{1}, 1, 1, CONTEXT, Set.of("biller.gln"), false))
                .isInstanceOf(VisionInvoiceFieldExtractor.PageExtractionException.class);
    }

    @Test
    void extract_emptyAnswer_throws() {
        when(callResponseSpec.content()).thenReturn(null);

        assertThatThrownBy(() -> extractor.extract(new byte[]{1}, 1, 1, CONTEXT, Set.of("biller.gln"), false))
                .isInstanceOf(VisionInvoiceFieldExtractor.PageExtractionException.class);
    }

    @Test
    void extract_modelCallFails_throws() {
        when(requestSpec.call()).thenThrow(new IllegalStateException("connection refused"));

        assertThatThrownBy(() -> extractor.extract(new byte[]{1}, 1, 1, CONTEXT, Set.of("biller.gln"), false))
                .isInstanceOf(VisionInvoiceFieldExtractor.PageExtractionException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void extract_invalidServiceLine_isSkippedWithWarning() {
        when(callResponseSpec.content()).thenReturn("""
                {"services": [{"code": "1001", "amount": "not a number"}, {"code": "1002", "amount": 10}]}
                """);

        PageExtraction page = extractor.extract(new byte[]{1}, 1, 1, CONTEXT, Set.of(), true);

        assertThat(page.services()).extracting(ExtractedService::code).containsExactly("1002");
        assertThat(page.warnings()).containsExactly("A service line could not be read");
    }

    @Test
    void buildUserPrompt_listsRequestedKeysAndHints() {
        Set<String> requested = new LinkedHashSet<>(List.of("biller.gln", "treatment.date_begin"));

        String prompt = VisionInvoiceFieldExtractor.buildUserPrompt(1, 2, CONTEXT, requested, false);

        assertThat(prompt)
                .contains("Page 1 of 2.")
                .contains("Expected language of the document: de.")
                .contains("- biller.gln\n- treatment.date_begin\n")
                .contains("Do not extract service lines")
                .contains("- patient.familyname = Güntensperger");
    }

    @Test
    void buildUserPrompt_noRequestedKey_asksForEmptyFields() {
        String prompt = VisionInvoiceFieldExtractor.buildUserPrompt(1, 1,
                new ExtractionContext(null, null, null, null, List.of("services")), Set.of(), true);

        assertThat(prompt)
                .contains("Do not extract any key")
                .contains("Also extract all the service lines")
                .doesNotContain("already known");
    }
}
