package zas.admin.zia.translation.service.llm;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import zas.admin.zia.translation.service.image.VisionImagePreprocessor;
import zas.admin.zia.translation.service.language.TargetLanguage;

import java.util.ArrayList;
import java.util.List;

@Service
public class TextTranslationService {

    private static final String LANGUAGE_INSTRUCTION =
            """
            The target language is %1$s.
            Your answer MUST be written entirely in %2$s, whatever the language of the source text \
            or of these instructions. Do not answer in any other language.
            """;

    private static final String TRANSLATE_PROMPT_TEMPLATE =
            """
            Translate the following text into %1$s.
            %3$s\
            Preserve all Markdown formatting (headings, tables, lists) exactly as-is.
            Return only the translated text, without any commentary or additional explanation.

            Text to translate into %2$s:

            %4$s
            """;

    private static final String TRANSLATE_PLAIN_PROMPT_TEMPLATE =
            """
            Translate the following text into %1$s.
            %3$s\
            Return only the translated text, without any commentary, formatting, or additional explanation.

            Text to translate into %2$s:

            %4$s
            """;

    private static final String SINGLE_STRATEGY_PROMPT_TEMPLATE =
            """
            Extract all the text from this image and translate it into %1$s.
            %3$s\
            Preserve the document structure using Markdown: headings (#, ##), tables (| col |), bullet lists (- item).
            Return only the translated text in Markdown format, without any commentary or additional explanation.
            """;

    private static final String SINGLE_STRATEGY_PLAIN_PROMPT_TEMPLATE =
            """
            Extract all the text from this image and translate it into %1$s.
            %3$s\
            Return only the translated text, without any commentary, formatting, or additional explanation.
            """;

    private final ChatClient llmClient;
    private final ChatClient visionClient;
    private final VisionImagePreprocessor imagePreprocessor;

    public TextTranslationService(
            @Qualifier("llmChatClient") ChatClient llmClient,
            @Qualifier("visionChatClient") ChatClient visionClient,
            VisionImagePreprocessor imagePreprocessor) {

        this.llmClient = llmClient;
        this.visionClient = visionClient;
        this.imagePreprocessor = imagePreprocessor;
    }

    public List<String> translatePages(List<String> extractedPages, TargetLanguage targetLanguage) {
        return translatePages(extractedPages, targetLanguage, true);
    }

    public List<String> translatePages(List<String> extractedPages, TargetLanguage targetLanguage, boolean renderAsMarkdown) {
        String template = renderAsMarkdown ? TRANSLATE_PROMPT_TEMPLATE : TRANSLATE_PLAIN_PROMPT_TEMPLATE;
        List<String> translated = new ArrayList<>(extractedPages.size());
        for (String pageText : extractedPages) {
            String prompt = buildPrompt(template, targetLanguage, pageText);
            String result = llmClient.prompt()
                    .user(prompt)
                    .call()
                    .content();
            translated.add(result != null ? result : "");
        }
        return translated;
    }

    public List<String> translatePagesSingleStrategy(List<byte[]> pageImages, TargetLanguage targetLanguage) {
        return translatePagesSingleStrategy(pageImages, targetLanguage, true);
    }

    public List<String> translatePagesSingleStrategy(List<byte[]> pageImages, TargetLanguage targetLanguage, boolean renderAsMarkdown) {
        String promptTemplate = renderAsMarkdown ? SINGLE_STRATEGY_PROMPT_TEMPLATE : SINGLE_STRATEGY_PLAIN_PROMPT_TEMPLATE;
        List<String> translated = new ArrayList<>(pageImages.size());
        for (byte[] imageBytes : pageImages) {
            String prompt = buildPrompt(promptTemplate, targetLanguage, "");
            Media media = imagePreprocessor.toMedia(imageBytes);
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
     * Translates a single plain text in one blocking (non-streamed) LLM call.
     */
    public String translateText(String text, TargetLanguage targetLanguage) {
        String prompt = buildPrompt(TRANSLATE_PLAIN_PROMPT_TEMPLATE, targetLanguage, text);
        String result = llmClient.prompt()
                .user(prompt)
                .call()
                .content();
        return result != null ? result : "";
    }

    /**
     * Streams the translation of a single text page token-by-token.
     */
    public Flux<String> translatePageStream(String extractedText, TargetLanguage targetLanguage) {
        String prompt = buildPrompt(TRANSLATE_PLAIN_PROMPT_TEMPLATE, targetLanguage, extractedText);
        return llmClient.prompt()
                .user(prompt)
                .stream()
                .content();
    }

    /**
     * Streams the translation of a single image page (single strategy) token-by-token.
     */
    public Flux<String> translatePageSingleStrategyStream(byte[] imageBytes, TargetLanguage targetLanguage) {
        String prompt = buildPrompt(SINGLE_STRATEGY_PLAIN_PROMPT_TEMPLATE, targetLanguage, "");
        Media media = imagePreprocessor.toMedia(imageBytes);
        UserMessage message = UserMessage.builder()
                .text(prompt)
                .media(media)
                .build();
        return visionClient.prompt()
                .messages(message)
                .stream()
                .content();
    }

    static String buildPrompt(String template, TargetLanguage targetLanguage, String text) {
        String languageInstruction = LANGUAGE_INSTRUCTION.formatted(targetLanguage.promptLabel(), targetLanguage.englishName());
        return template.formatted(targetLanguage.promptLabel(), targetLanguage.englishName(), languageInstruction, text);
    }
}
