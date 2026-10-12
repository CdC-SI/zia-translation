package zas.admin.zia.translation.service.ocr;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import zas.admin.zia.translation.service.image.VisionImagePreprocessor;

import java.util.ArrayList;
import java.util.List;

@Service
public class OcrExtractionService {

    private static final String OCR_PROMPT =
            """
            Extract all the text from this image exactly as it appears.
            Preserve the document structure using Markdown: use headings (#, ##, etc.) for titles,
            pipe-delimited tables (| col1 | col2 |) for tabular data, and bullet lists (- item) for lists.
            Return only the extracted text in Markdown format, without any commentary.
            """;

    private final ChatClient visionClient;
    private final VisionImagePreprocessor imagePreprocessor;

    OcrExtractionService(@Qualifier("visionChatClient") ChatClient visionClient, VisionImagePreprocessor imagePreprocessor) {
        this.visionClient = visionClient;
        this.imagePreprocessor = imagePreprocessor;
    }

    public List<String> extractText(List<byte[]> pageImages) {
        List<String> extracted = new ArrayList<>(pageImages.size());
        for (byte[] imageBytes : pageImages) {
            Media media = imagePreprocessor.toMedia(imageBytes);
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
