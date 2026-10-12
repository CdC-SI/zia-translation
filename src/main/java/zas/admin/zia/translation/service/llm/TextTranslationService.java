package zas.admin.zia.translation.service.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import zas.admin.zia.translation.service.glossary.AcronymLookupTool;
import zas.admin.zia.translation.service.glossary.Glossary;
import zas.admin.zia.translation.service.image.VisionImagePreprocessor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class TextTranslationService {

    private static final Logger log = LoggerFactory.getLogger(TextTranslationService.class);

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

    // Placed before the translation prompt when the acronym lookup tool is offered.
    private static final String ACRONYM_TOOL_INSTRUCTION =
            """
            If the source text is in German, French, Italian or English, call the lookup_acronyms tool once before translating,
            with every acronym of the source text and the language of the source text.
            For each acronym it finds, write one of its targetForms in the translation. Keep acronyms it does not find unchanged.

            """;

    private final ChatClient llmClient;
    private final ChatClient visionClient;
    private final Optional<AcronymLookupTool> acronymLookupTool;
    private final VisionImagePreprocessor imagePreprocessor;

    /**
     * @param acronymLookupTool present only when {@code zia.translation.glossary.tool-enabled=true}
     */
    public TextTranslationService(
            @Qualifier("llmChatClient") ChatClient llmClient,
            @Qualifier("visionChatClient") ChatClient visionClient,
            VisionImagePreprocessor imagePreprocessor,
            Optional<AcronymLookupTool> acronymLookupTool) {

        this.llmClient = llmClient;
        this.visionClient = visionClient;
        this.imagePreprocessor = imagePreprocessor;
        this.acronymLookupTool = acronymLookupTool;
    }

    public List<String> translatePages(List<String> extractedPages, String targetLanguage) {
        return translatePages(extractedPages, targetLanguage, true);
    }

    public List<String> translatePages(List<String> extractedPages, String targetLanguage, boolean renderAsMarkdown) {
        String template = renderAsMarkdown ? TRANSLATE_PROMPT_TEMPLATE : TRANSLATE_PLAIN_PROMPT_TEMPLATE;
        Optional<String> toolLanguage = acronymToolLanguage(targetLanguage);
        List<String> translated = new ArrayList<>(extractedPages.size());
        for (String pageText : extractedPages) {
            String prompt = withToolInstruction(template.formatted(targetLanguage, pageText), toolLanguage);
            String result = withAcronymTool(llmClient.prompt().user(prompt), toolLanguage)
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
        Optional<String> toolLanguage = acronymToolLanguage(targetLanguage);
        List<String> translated = new ArrayList<>(pageImages.size());
        for (byte[] imageBytes : pageImages) {
            String prompt = withToolInstruction(promptTemplate.formatted(targetLanguage), toolLanguage);
            Media media = imagePreprocessor.toMedia(imageBytes);
            UserMessage message = UserMessage.builder()
                    .text(prompt)
                    .media(media)
                    .build();
            String result = withAcronymTool(visionClient.prompt().messages(message), toolLanguage)
                    .call()
                    .content();
            translated.add(result != null ? result : "");
        }
        return translated;
    }

    /**
     * Translates a single plain text in one blocking (non-streamed) LLM call.
     */
    public String translateText(String text, String targetLanguage) {
        String prompt = TRANSLATE_PLAIN_PROMPT_TEMPLATE.formatted(targetLanguage, text);
        String result = llmClient.prompt()
                .user(prompt)
                .call()
                .content();
        return result != null ? result : "";
    }

    /**
     * Streams the translation of a single text page token-by-token.
     */
    public Flux<String> translatePageStream(String extractedText, String targetLanguage) {
        Optional<String> toolLanguage = acronymToolLanguage(targetLanguage);
        String prompt = withToolInstruction(TRANSLATE_PLAIN_PROMPT_TEMPLATE.formatted(targetLanguage, extractedText), toolLanguage);
        return withAcronymTool(llmClient.prompt().user(prompt), toolLanguage)
                .stream()
                .content();
    }

    /**
     * Streams the translation of a single image page (single strategy) token-by-token.
     */
    public Flux<String> translatePageSingleStrategyStream(byte[] imageBytes, String targetLanguage) {
        Optional<String> toolLanguage = acronymToolLanguage(targetLanguage);
        String prompt = withToolInstruction(SINGLE_STRATEGY_PLAIN_PROMPT_TEMPLATE.formatted(targetLanguage), toolLanguage);
        Media media = imagePreprocessor.toMedia(imageBytes);
        UserMessage message = UserMessage.builder()
                .text(prompt)
                .media(media)
                .build();
        return withAcronymTool(visionClient.prompt().messages(message), toolLanguage)
                .stream()
                .content();
    }

    /**
     * The glossary language to look acronyms up for, or empty when the tool is disabled or the glossary
     * doesn't cover the target language.
     */
    private Optional<String> acronymToolLanguage(String targetLanguage) {
        if (acronymLookupTool.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> language = Glossary.languageCode(targetLanguage);
        if (language.isEmpty()) {
            log.info("Acronym lookup tool not offered: the glossary has no language matching '{}'", targetLanguage);
        }
        return language;
    }

    private static String withToolInstruction(String prompt, Optional<String> toolLanguage) {
        return toolLanguage.isPresent() ? ACRONYM_TOOL_INSTRUCTION + prompt : prompt;
    }

    /**
     * Offers the lookup_acronyms tool to the model. The target language goes in the tool context, which
     * Spring AI hands to the tool method but never sends to the model.
     */
    private ChatClient.ChatClientRequestSpec withAcronymTool(ChatClient.ChatClientRequestSpec request, Optional<String> toolLanguage) {
        if (toolLanguage.isEmpty()) {
            return request;
        }
        return request
                .tools(acronymLookupTool.orElseThrow())
                .toolContext(Map.of(AcronymLookupTool.TARGET_LANGUAGE, toolLanguage.get()));
    }
}
