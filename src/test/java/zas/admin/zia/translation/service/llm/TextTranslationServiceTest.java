package zas.admin.zia.translation.service.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TextTranslationServiceTest {

    @Mock
    private ChatClient llmClient;

    @Mock
    private ChatClient visionClient;

    @Test
    void translatePages_singlePage_returnsTranslation() {
        stubLlmClient("Texte traduit");
        TextTranslationService service = new TextTranslationService(llmClient, visionClient);

        List<String> result = service.translatePages(List.of("Source text"), "fr");

        assertThat(result).containsExactly("Texte traduit");
    }

    @Test
    void translatePages_multiplePages_returnsOneTranslationPerPage() {
        stubLlmClient("Translated");
        TextTranslationService service = new TextTranslationService(llmClient, visionClient);

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

        TextTranslationService service = new TextTranslationService(llmClient, visionClient);
        List<String> result = service.translatePages(List.of("text"), "fr");

        assertThat(result).containsExactly("");
    }

    @Test
    void translatePagesSingleStrategy_singlePage_returnsTranslation() {
        ChatClient.CallResponseSpec callResponseSpec = mock(ChatClient.CallResponseSpec.class);
        when(callResponseSpec.content()).thenReturn("Translated via vision");

        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        when(requestSpec.messages(any(Message.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);

        when(visionClient.prompt()).thenReturn(requestSpec);

        TextTranslationService service = new TextTranslationService(llmClient, visionClient);
        List<String> result = service.translatePagesSingleStrategy(List.of(new byte[]{1, 2, 3}), "fr");

        assertThat(result).containsExactly("Translated via vision");
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
