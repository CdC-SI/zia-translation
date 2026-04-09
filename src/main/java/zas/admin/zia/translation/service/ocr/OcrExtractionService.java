package zas.admin.zia.translation.service.ocr;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;

import java.util.ArrayList;
import java.util.List;

@Service
public class OcrExtractionService {

    private static final String OCR_PROMPT =
            "Extract all the text from this image exactly as it appears. " +
            "Return only the extracted text, without any commentary or formatting.";

    private final ChatClient visionClient;

    OcrExtractionService(@Qualifier("visionChatClient") ChatClient visionClient) {
        this.visionClient = visionClient;
    }

    public List<String> extractText(List<byte[]> pageImages) {
        List<String> extracted = new ArrayList<>(pageImages.size());
        for (byte[] imageBytes : pageImages) {
            Media media = new Media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(imageBytes));
            UserMessage message = UserMessage.builder()
                    .text(OCR_PROMPT)
                    .media(media)
                    .build();
            String text = visionClient.prompt()
                    .messages(message)
                    .call()
                    .content();
            extracted.add(text != null ? text : "");
        }
        return extracted;
    }
}
