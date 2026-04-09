package zas.admin.zia.translation.service.ocr;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
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
class OcrExtractionServiceTest {

    @Mock
    private ChatClient visionClient;

    @InjectMocks
    private OcrExtractionService service;

    @Test
    void extractText_singlePage_returnsExtractedText() {
        stubVisionClient("Extracted text from page");

        List<String> result = service.extractText(List.of(new byte[]{1, 2, 3}));

        assertThat(result).containsExactly("Extracted text from page");
    }

    @Test
    void extractText_multiplePages_returnsOneEntryPerPage() {
        stubVisionClient("page text");

        List<byte[]> pages = List.of(new byte[]{1}, new byte[]{2}, new byte[]{3});
        List<String> result = service.extractText(pages);

        assertThat(result).hasSize(3);
    }

    @Test
    void extractText_nullResponseContent_returnsEmptyString() {
        ChatClient.CallResponseSpec callResponseSpec = mock(ChatClient.CallResponseSpec.class);
        when(callResponseSpec.content()).thenReturn(null);

        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        when(requestSpec.messages(any(Message.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);

        when(visionClient.prompt()).thenReturn(requestSpec);

        List<String> result = service.extractText(List.of(new byte[]{1}));

        assertThat(result).containsExactly("");
    }

    private void stubVisionClient(String content) {
        ChatClient.CallResponseSpec callResponseSpec = mock(ChatClient.CallResponseSpec.class);
        when(callResponseSpec.content()).thenReturn(content);

        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        when(requestSpec.messages(any(Message.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);

        when(visionClient.prompt()).thenReturn(requestSpec);
    }
}
