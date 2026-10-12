package zas.admin.zia.translation.service.ocr;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;
import zas.admin.zia.translation.service.image.VisionImagePreprocessor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OcrExtractionServiceTest {

    @Mock
    private ChatClient visionClient;

    @Mock
    private VisionImagePreprocessor imagePreprocessor;

    @InjectMocks
    private OcrExtractionService service;

    @Test
    void extractText_singlePage_returnsExtractedText() {
        stubPreprocessor();
        stubVisionClient("Extracted text from page");

        List<String> result = service.extractText(List.of(new byte[]{1, 2, 3}));

        assertThat(result).containsExactly("Extracted text from page");
    }

    @Test
    void extractText_multiplePages_returnsOneEntryPerPage() {
        stubPreprocessor();
        stubVisionClient("page text");

        List<byte[]> pages = List.of(new byte[]{1}, new byte[]{2}, new byte[]{3});
        List<String> result = service.extractText(pages);

        assertThat(result).hasSize(3);
    }

    @Test
    void extractText_nullResponseContent_returnsEmptyString() {
        stubPreprocessor();
        ChatClient.CallResponseSpec callResponseSpec = mock(ChatClient.CallResponseSpec.class);
        when(callResponseSpec.content()).thenReturn(null);

        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        when(requestSpec.messages(any(Message.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);

        when(visionClient.prompt()).thenReturn(requestSpec);

        List<String> result = service.extractText(List.of(new byte[]{1}));

        assertThat(result).containsExactly("");
    }

    @Test
    void extractText_usesPreprocessedJpegMedia() {
        stubVisionClient("Extracted text from page");
        ArgumentCaptor<byte[]> bytesCaptor = ArgumentCaptor.forClass(byte[].class);
        Media jpegMedia = new Media(MimeTypeUtils.IMAGE_JPEG, new ByteArrayResource(new byte[]{9, 9, 9}));
        when(imagePreprocessor.toMedia(bytesCaptor.capture())).thenReturn(jpegMedia);

        service.extractText(List.of(new byte[]{1, 2, 3}));

        verify(imagePreprocessor).toMedia(any(byte[].class));
        assertThat(bytesCaptor.getValue()).containsExactly(1, 2, 3);
    }

    private void stubPreprocessor() {
        Media jpegMedia = new Media(MimeTypeUtils.IMAGE_JPEG, new ByteArrayResource(new byte[]{9, 9, 9}));
        when(imagePreprocessor.toMedia(any(byte[].class))).thenReturn(jpegMedia);
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
