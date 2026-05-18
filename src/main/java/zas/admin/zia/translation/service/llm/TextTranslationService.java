package zas.admin.zia.translation.service.llm;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

@Service
public class TextTranslationService {

    private static final String TRANSLATE_PROMPT_TEMPLATE =
            """
            Translate the following text to %s.
            Preserve all Markdown formatting (headings, tables, lists) exactly as-is.
            Return only the translated text, without any commentary or additional explanation.
            
            %s
            """;

    private static final String TRANSLATE_PLAIN_PROMPT_TEMPLATE =
            """
            Translate the following text to %s.
            Return only the translated text, without any commentary, formatting, or additional explanation.
            
            %s
            """;

    private static final String SINGLE_STRATEGY_PROMPT_TEMPLATE =
            """
            Extract all the text from this image and translate it to %s.
            Preserve the document structure using Markdown: headings (#, ##), tables (| col |), bullet lists (- item).
            Return only the translated text in Markdown format, without any commentary or additional explanation.
            """;

    private static final String SINGLE_STRATEGY_PLAIN_PROMPT_TEMPLATE =
            """
            Extract all the text from this image and translate it to %s.
            Return only the translated text, without any commentary, formatting, or additional explanation.
            """;

    private final ChatClient llmClient;
    private final ChatClient visionClient;

    public TextTranslationService(
            @Qualifier("llmChatClient") ChatClient llmClient,
            @Qualifier("visionChatClient") ChatClient visionClient) {

        this.llmClient = llmClient;
        this.visionClient = visionClient;
    }

    public List<String> translatePages(List<String> extractedPages, String targetLanguage) {
        return translatePages(extractedPages, targetLanguage, true);
    }

    public List<String> translatePages(List<String> extractedPages, String targetLanguage, boolean renderAsMarkdown) {
        String template = renderAsMarkdown ? TRANSLATE_PROMPT_TEMPLATE : TRANSLATE_PLAIN_PROMPT_TEMPLATE;
        List<String> translated = new ArrayList<>(extractedPages.size());
        for (String pageText : extractedPages) {
            String prompt = template.formatted(targetLanguage, pageText);
            String result = llmClient.prompt()
                    .user(prompt)
                    .call()
                    .content();
            translated.add(result != null ? result : "");
        }
        return translated;
    }

    public List<String> translatePagesSingleStrategy(List<byte[]> pageImages, String targetLanguage) {
        return translatePagesSingleStrategy(pageImages, targetLanguage, true);
    }

    public List<String> translatePagesSingleStrategy(List<byte[]> pageImages, String targetLanguage, boolean renderAsMarkdown) {
        String promptTemplate = renderAsMarkdown ? SINGLE_STRATEGY_PROMPT_TEMPLATE : SINGLE_STRATEGY_PLAIN_PROMPT_TEMPLATE;
        List<String> translated = new ArrayList<>(pageImages.size());
        for (byte[] imageBytes : pageImages) {
            String prompt = promptTemplate.formatted(targetLanguage);
            Media media = new Media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(imageBytes));
            UserMessage message = UserMessage.builder()
                    .text(prompt)
                    .media(media)
                    .build();
            String result = visionClient.prompt()
                    .messages(message)
                    .call()
                    .content();
            translated.add(result != null ? result : "");
        }
        return translated;
    }

    /**
     * Streams the translation of a single text page token-by-token.
     */
    public Flux<String> translatePageStream(String extractedText, String targetLanguage) {
        String prompt = TRANSLATE_PLAIN_PROMPT_TEMPLATE.formatted(targetLanguage, extractedText);
        return llmClient.prompt()
                .user(prompt)
                .stream()
                .content();
    }

    /**
     * Streams the translation of a single image page (single strategy) token-by-token.
     */
    public Flux<String> translatePageSingleStrategyStream(byte[] imageBytes, String targetLanguage) {
        String prompt = SINGLE_STRATEGY_PLAIN_PROMPT_TEMPLATE.formatted(targetLanguage);
        Media media = new Media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(imageBytes));
        UserMessage message = UserMessage.builder()
                .text(prompt)
                .media(media)
                .build();
        return visionClient.prompt()
                .messages(message)
                .stream()
                .content();
    }
}
