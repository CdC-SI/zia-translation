package zas.admin.zia.translation.service.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import zas.admin.zia.translation.service.glossary.AcronymLookupTool;
import zas.admin.zia.translation.service.image.VisionImagePreprocessor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TextTranslationServiceTest {

    @Mock
    private ChatClient llmClient;

    @Mock
    private ChatClient visionClient;

    @Mock
    private AcronymLookupTool acronymLookupTool;

    @Mock
    private VisionImagePreprocessor imagePreprocessor;

    @Test
    void translatePages_singlePage_returnsTranslation() {
        stubLlmClient("Texte traduit");

        TextTranslationService service = new TextTranslationService(llmClient, visionClient, imagePreprocessor, Optional.empty());

        List<String> result = service.translatePages(List.of("Source text"), "fr");

        assertThat(result).containsExactly("Texte traduit");
    }

    @Test
    void translatePages_multiplePages_returnsOneTranslationPerPage() {
        stubLlmClient("Translated");

        TextTranslationService service = new TextTranslationService(llmClient, visionClient, imagePreprocessor, Optional.empty());

        List<String> result = service.translatePages(List.of("Page 1", "Page 2", "Page 3"), "de");

        assertThat(result).hasSize(3);
    }

    @Test
    void translatePages_nullContent_returnsEmptyString() {
        ChatClient.CallResponseSpec callResponseSpec = mock(ChatClient.CallResponseSpec.class);
        when(callResponseSpec.content()).thenReturn(null);

        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        when(requestSpec.user(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);

        when(llmClient.prompt()).thenReturn(requestSpec);

        TextTranslationService service = new TextTranslationService(llmClient, visionClient, imagePreprocessor, Optional.empty());

        List<String> result = service.translatePages(List.of("text"), "fr");

        assertThat(result).containsExactly("");
    }

    @Test
    void translatePagesSingleStrategy_singlePage_returnsTranslation() {
        stubImagePreprocessor();
        ChatClient.CallResponseSpec callResponseSpec = mock(ChatClient.CallResponseSpec.class);
        when(callResponseSpec.content()).thenReturn("Translated via vision");

        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        when(requestSpec.messages(any(Message.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);

        when(visionClient.prompt()).thenReturn(requestSpec);

        TextTranslationService service = new TextTranslationService(llmClient, visionClient, imagePreprocessor, Optional.empty());

        List<String> result = service.translatePagesSingleStrategy(List.of(new byte[]{1, 2, 3}), "fr");

        assertThat(result).containsExactly("Translated via vision");
    }

    @Test
    void translatePages_renderAsMarkdownFalse_doesNotIncludeMarkdownInstructions() {
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);

        ChatClient.CallResponseSpec callResponseSpec = mock(ChatClient.CallResponseSpec.class);
        when(callResponseSpec.content()).thenReturn("Plain translated text");

        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        when(requestSpec.user(promptCaptor.capture())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);

        when(llmClient.prompt()).thenReturn(requestSpec);

        TextTranslationService service = new TextTranslationService(llmClient, visionClient, imagePreprocessor, Optional.empty());

        List<String> result = service.translatePages(List.of("Some text"), "fr", false);

        assertThat(result).containsExactly("Plain translated text");
        assertThat(promptCaptor.getValue())
                .doesNotContain("Markdown")
                .doesNotContain("headings")
                .doesNotContain("tables");
    }

    @Test
    void translatePages_renderAsMarkdownTrue_includesMarkdownInstructions() {
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);

        ChatClient.CallResponseSpec callResponseSpec = mock(ChatClient.CallResponseSpec.class);
        when(callResponseSpec.content()).thenReturn("# Markdown translated text");

        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        when(requestSpec.user(promptCaptor.capture())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);

        when(llmClient.prompt()).thenReturn(requestSpec);

        TextTranslationService service = new TextTranslationService(llmClient, visionClient, imagePreprocessor, Optional.empty());

        List<String> result = service.translatePages(List.of("Some text"), "fr", true);

        assertThat(result).containsExactly("# Markdown translated text");
        assertThat(promptCaptor.getValue()).contains("Markdown");
    }

    @Test
    void translatePagesSingleStrategy_renderAsMarkdownFalse_doesNotIncludeMarkdownInstructions() {
        stubImagePreprocessor();
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);

        ChatClient.CallResponseSpec callResponseSpec = mock(ChatClient.CallResponseSpec.class);
        when(callResponseSpec.content()).thenReturn("Plain vision result");

        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        when(requestSpec.messages(messageCaptor.capture())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);

        when(visionClient.prompt()).thenReturn(requestSpec);

        TextTranslationService service = new TextTranslationService(llmClient, visionClient, imagePreprocessor, Optional.empty());

        List<String> result = service.translatePagesSingleStrategy(List.of(new byte[]{1, 2, 3}), "fr", false);

        assertThat(result).containsExactly("Plain vision result");
        assertThat(messageCaptor.getValue().getText())
                .doesNotContain("Markdown")
                .doesNotContain("headings")
                .doesNotContain("tables");
    }

    @Test
    void translatePagesSingleStrategy_renderAsMarkdownTrue_includesMarkdownInstructions() {
        stubImagePreprocessor();
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);

        ChatClient.CallResponseSpec callResponseSpec = mock(ChatClient.CallResponseSpec.class);
        when(callResponseSpec.content()).thenReturn("# Vision markdown result");

        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        when(requestSpec.messages(messageCaptor.capture())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);

        when(visionClient.prompt()).thenReturn(requestSpec);

        TextTranslationService service = new TextTranslationService(llmClient, visionClient, imagePreprocessor, Optional.empty());

        List<String> result = service.translatePagesSingleStrategy(List.of(new byte[]{1, 2, 3}), "fr", true);

        assertThat(result).containsExactly("# Vision markdown result");
        assertThat(messageCaptor.getValue().getText()).contains("Markdown");
    }

    @Test
    void translatePageStream_streamsTokens() {
        ChatClient.StreamResponseSpec streamResponseSpec = mock(ChatClient.StreamResponseSpec.class);
        when(streamResponseSpec.content()).thenReturn(Flux.just("Bonjour", " le", " monde"));

        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        when(requestSpec.user(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamResponseSpec);

        when(llmClient.prompt()).thenReturn(requestSpec);

        TextTranslationService service = new TextTranslationService(llmClient, visionClient, imagePreprocessor, Optional.empty());

        StepVerifier.create(service.translatePageStream("Hello world", "fr"))
                .expectNext("Bonjour")
                .expectNext(" le")
                .expectNext(" monde")
                .verifyComplete();
    }

    @Test
    void translatePageSingleStrategyStream_streamsTokens() {
        stubImagePreprocessor();
        ChatClient.StreamResponseSpec streamResponseSpec = mock(ChatClient.StreamResponseSpec.class);
        when(streamResponseSpec.content()).thenReturn(Flux.just("Token1", "Token2"));

        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        when(requestSpec.messages(any(Message.class))).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamResponseSpec);

        when(visionClient.prompt()).thenReturn(requestSpec);

        TextTranslationService service = new TextTranslationService(llmClient, visionClient, imagePreprocessor, Optional.empty());

        StepVerifier.create(service.translatePageSingleStrategyStream(new byte[]{1, 2, 3}, "de"))
                .expectNext("Token1")
                .expectNext("Token2")
                .verifyComplete();
    }

    @Test
    void translatePages_toolEnabled_offersAcronymToolWithTargetLanguage() {
        ChatClient.ChatClientRequestSpec requestSpec = selfReturningCallSpec("Texte");
        when(llmClient.prompt()).thenReturn(requestSpec);
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);

        withTool().translatePages(List.of("Die AHV"), "fr");

        verify(requestSpec).user(promptCaptor.capture());
        verify(requestSpec).tools(acronymLookupTool);
        verify(requestSpec).toolContext(Map.of(AcronymLookupTool.TARGET_LANGUAGE, "fr"));
        assertThat(promptCaptor.getValue()).contains("lookup_acronyms").contains("Die AHV");
    }

    @Test
    void translatePagesSingleStrategy_toolEnabled_offersAcronymTool() {
        stubImagePreprocessor();
        ChatClient.ChatClientRequestSpec requestSpec = selfReturningCallSpec("Texte");
        when(visionClient.prompt()).thenReturn(requestSpec);
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);

        withTool().translatePagesSingleStrategy(List.of(new byte[]{1, 2, 3}), "French");

        verify(requestSpec).messages(messageCaptor.capture());
        verify(requestSpec).tools(acronymLookupTool);
        verify(requestSpec).toolContext(Map.of(AcronymLookupTool.TARGET_LANGUAGE, "fr"));
        assertThat(messageCaptor.getValue().getText()).contains("lookup_acronyms");
    }

    @Test
    void translatePageStream_toolEnabled_offersAcronymTool() {
        ChatClient.ChatClientRequestSpec requestSpec = selfReturningStreamSpec(Flux.just("Bonjour"));
        when(llmClient.prompt()).thenReturn(requestSpec);

        StepVerifier.create(withTool().translatePageStream("Guten Tag", "de"))
                .expectNext("Bonjour")
                .verifyComplete();

        verify(requestSpec).tools(acronymLookupTool);
        verify(requestSpec).toolContext(Map.of(AcronymLookupTool.TARGET_LANGUAGE, "de"));
    }

    @Test
    void translatePageSingleStrategyStream_toolEnabled_offersAcronymTool() {
        stubImagePreprocessor();
        ChatClient.ChatClientRequestSpec requestSpec = selfReturningStreamSpec(Flux.just("Token"));
        when(visionClient.prompt()).thenReturn(requestSpec);

        StepVerifier.create(withTool().translatePageSingleStrategyStream(new byte[]{1, 2, 3}, "it"))
                .expectNext("Token")
                .verifyComplete();

        verify(requestSpec).tools(acronymLookupTool);
        verify(requestSpec).toolContext(Map.of(AcronymLookupTool.TARGET_LANGUAGE, "it"));
    }

    @Test
    void translatePages_toolEnabledButLanguageNotInGlossary_doesNotOfferTool() {
        ChatClient.ChatClientRequestSpec requestSpec = selfReturningCallSpec("Texto");
        when(llmClient.prompt()).thenReturn(requestSpec);
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);

        withTool().translatePages(List.of("Die AHV"), "es");

        verify(requestSpec).user(promptCaptor.capture());
        verify(requestSpec, never()).tools(any());
        assertThat(promptCaptor.getValue()).doesNotContain("lookup_acronyms");
    }

    @Test
    void translatePages_toolDisabled_doesNotOfferTool() {
        ChatClient.ChatClientRequestSpec requestSpec = selfReturningCallSpec("Texte");
        when(llmClient.prompt()).thenReturn(requestSpec);
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);

        new TextTranslationService(llmClient, visionClient, imagePreprocessor, Optional.empty()).translatePages(List.of("Die AHV"), "fr");

        verify(requestSpec).user(promptCaptor.capture());
        verify(requestSpec, never()).tools(any());
        verify(requestSpec, never()).toolContext(any());
        assertThat(promptCaptor.getValue()).doesNotContain("lookup_acronyms");
    }

    private TextTranslationService withTool() {
        return new TextTranslationService(llmClient, visionClient, imagePreprocessor, Optional.of(acronymLookupTool));
    }

    private static ChatClient.ChatClientRequestSpec selfReturningCallSpec(String content) {
        ChatClient.CallResponseSpec callResponseSpec = mock(ChatClient.CallResponseSpec.class);
        when(callResponseSpec.content()).thenReturn(content);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class, RETURNS_SELF);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        return requestSpec;
    }

    private static ChatClient.ChatClientRequestSpec selfReturningStreamSpec(Flux<String> tokens) {
        ChatClient.StreamResponseSpec streamResponseSpec = mock(ChatClient.StreamResponseSpec.class);
        when(streamResponseSpec.content()).thenReturn(tokens);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class, RETURNS_SELF);
        when(requestSpec.stream()).thenReturn(streamResponseSpec);
        return requestSpec;
        }

    @Test
    void translatePagesSingleStrategy_usesPreprocessedJpegMedia() {
        stubImagePreprocessor();
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);

        ChatClient.CallResponseSpec callResponseSpec = mock(ChatClient.CallResponseSpec.class);
        when(callResponseSpec.content()).thenReturn("Translated via vision");

        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        when(requestSpec.messages(messageCaptor.capture())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);

        when(visionClient.prompt()).thenReturn(requestSpec);

        TextTranslationService service = new TextTranslationService(llmClient, visionClient, imagePreprocessor, Optional.empty());
        service.translatePagesSingleStrategy(List.of(new byte[]{1, 2, 3}), "fr");

        verify(imagePreprocessor).toMedia(any(byte[].class));
        UserMessage message = (UserMessage) messageCaptor.getValue();
        assertThat(message.getMedia().get(0).getMimeType()).isEqualTo(MimeTypeUtils.IMAGE_JPEG);
    }

    @Test
    void translatePageSingleStrategyStream_usesPreprocessedJpegMedia() {
        stubImagePreprocessor();
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);

        ChatClient.StreamResponseSpec streamResponseSpec = mock(ChatClient.StreamResponseSpec.class);
        when(streamResponseSpec.content()).thenReturn(Flux.just("Token1"));

        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        when(requestSpec.messages(messageCaptor.capture())).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamResponseSpec);

        when(visionClient.prompt()).thenReturn(requestSpec);

        TextTranslationService service = new TextTranslationService(llmClient, visionClient, imagePreprocessor, Optional.empty());

        StepVerifier.create(service.translatePageSingleStrategyStream(new byte[]{1, 2, 3}, "de"))
                .expectNext("Token1")
                .verifyComplete();

        verify(imagePreprocessor).toMedia(any(byte[].class));
        UserMessage message = (UserMessage) messageCaptor.getValue();
        assertThat(message.getMedia().get(0).getMimeType()).isEqualTo(MimeTypeUtils.IMAGE_JPEG);
    }

    private void stubImagePreprocessor() {
        Media jpegMedia = new Media(MimeTypeUtils.IMAGE_JPEG, new ByteArrayResource(new byte[]{9, 9, 9}));
        when(imagePreprocessor.toMedia(any(byte[].class))).thenReturn(jpegMedia);
    }

    @Test
    void translateText_returnsTranslation() {
        stubLlmClient("Bonjour le monde");
        TextTranslationService service = new TextTranslationService(llmClient, visionClient, imagePreprocessor, Optional.empty());

        String result = service.translateText("Hello world", "fr");

        assertThat(result).isEqualTo("Bonjour le monde");
    }

    @Test
    void translateText_usesPlainPrompt_nonStreamedCall() {
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);

        ChatClient.CallResponseSpec callResponseSpec = mock(ChatClient.CallResponseSpec.class);
        when(callResponseSpec.content()).thenReturn("Translated plain text");

        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        when(requestSpec.user(promptCaptor.capture())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);

        when(llmClient.prompt()).thenReturn(requestSpec);

        TextTranslationService service = new TextTranslationService(llmClient, visionClient, imagePreprocessor, Optional.empty());
        String result = service.translateText("Some text", "fr");

        assertThat(result).isEqualTo("Translated plain text");
        assertThat(promptCaptor.getValue())
                .doesNotContain("Markdown")
                .doesNotContain("headings")
                .doesNotContain("tables");
    }

    @Test
    void translateText_nullContent_returnsEmptyString() {
        ChatClient.CallResponseSpec callResponseSpec = mock(ChatClient.CallResponseSpec.class);
        when(callResponseSpec.content()).thenReturn(null);

        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        when(requestSpec.user(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);

        when(llmClient.prompt()).thenReturn(requestSpec);

        TextTranslationService service = new TextTranslationService(llmClient, visionClient, imagePreprocessor, Optional.empty());
        String result = service.translateText("text", "fr");

        assertThat(result).isEmpty();
    }

    private void stubLlmClient(String content) {
        ChatClient.CallResponseSpec callResponseSpec = mock(ChatClient.CallResponseSpec.class);
        when(callResponseSpec.content()).thenReturn(content);

        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        when(requestSpec.user(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);

        when(llmClient.prompt()).thenReturn(requestSpec);
    }
}
