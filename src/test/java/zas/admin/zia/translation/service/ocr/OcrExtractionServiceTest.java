package zas.admin.zia.translation.service.ocr;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;
import zas.admin.zia.translation.service.image.VisionImagePreprocessor;

import java.awt.image.BufferedImage;

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

    private static final BufferedImage PAGE = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);

    @Test
    void extractText_returnsExtractedText() {
        stubPreprocessor();
        stubVisionClient("Extracted text from page");

        assertThat(service.extractText(PAGE)).isEqualTo("Extracted text from page");
    }

    @Test
    void extractText_nullResponseContent_returnsEmptyString() {
        stubPreprocessor();
        stubVisionClient(null);

        assertThat(service.extractText(PAGE)).isEmpty();
    }

    @Test
    void extractText_sendsThePreprocessedPage() {
        stubVisionClient("Extracted text from page");
        Media jpegMedia = new Media(MimeTypeUtils.IMAGE_JPEG, new ByteArrayResource(new byte[]{9, 9, 9}));
        when(imagePreprocessor.toMedia(PAGE)).thenReturn(jpegMedia);

        service.extractText(PAGE);

        verify(imagePreprocessor).toMedia(PAGE);
    }

    private void stubPreprocessor() {
        Media jpegMedia = new Media(MimeTypeUtils.IMAGE_JPEG, new ByteArrayResource(new byte[]{9, 9, 9}));
        when(imagePreprocessor.toMedia(any(BufferedImage.class))).thenReturn(jpegMedia);
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
