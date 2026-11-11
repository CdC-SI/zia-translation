package zas.admin.zia.translation.service.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.ai.model.tool.ToolCallLimitExceededException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import zas.admin.zia.translation.service.ToolCallLimitReachedException;
import zas.admin.zia.translation.service.glossary.AcronymLookupTool;
import zas.admin.zia.translation.service.image.VisionImagePreprocessor;
import zas.admin.zia.translation.service.language.TargetLanguage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    // Added to the prompt only when the acronym lookup tool is offered (see withToolInstruction).
    private static final String ACRONYM_TOOL_INSTRUCTION =
            """
            Acronyms: you may call the lookup_acronyms tool ONCE, before translating, with all the acronyms of the source text together and the language of the source text. Web and e-mail addresses are not acronyms. Currency codes, units and article numbers are not acronyms. If the source text has no acronym, do not call the tool.
            - The tool result is final. Once you have it, do not call lookup_acronyms again (not with the same acronyms, not with other acronyms, not with another language): write the translation right away.
            - If the tool finds an acronym and one candidate's full name or definition matches its meaning in this text, write one of that candidate's targetForms at every occurrence of the acronym, also inside compound words.
            - If no candidate matches its meaning in this text, the acronym means something else here: translate it as you normally would.
            - If the tool does not find an acronym, translate it as you normally would.
            """;

    private static final Logger log = LoggerFactory.getLogger(TextTranslationService.class);

    private final ChatClient llmClient;
    private final ChatClient visionClient;
    private final VisionImagePreprocessor imagePreprocessor;
    private final Optional<AcronymLookupTool> acronymLookupTool;

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

    public List<String> translatePages(List<String> extractedPages, TargetLanguage targetLanguage) {
        return translatePages(extractedPages, targetLanguage, true);
    }

    public List<String> translatePages(List<String> extractedPages, TargetLanguage targetLanguage, boolean renderAsMarkdown) {
        String template = renderAsMarkdown ? TRANSLATE_PROMPT_TEMPLATE : TRANSLATE_PLAIN_PROMPT_TEMPLATE;
        Optional<String> toolLanguage = acronymToolLanguage(targetLanguage);
        List<String> translated = new ArrayList<>(extractedPages.size());
        for (String pageText : extractedPages) {
            String result = call(llmClient, buildPrompt(template, targetLanguage, pageText), null, toolLanguage);
            translated.add(result != null ? result : "");
        }
        return translated;
    }

    public List<String> translatePagesSingleStrategy(List<byte[]> pageImages, TargetLanguage targetLanguage) {
        return translatePagesSingleStrategy(pageImages, targetLanguage, true);
    }

    public List<String> translatePagesSingleStrategy(List<byte[]> pageImages, TargetLanguage targetLanguage, boolean renderAsMarkdown) {
        String promptTemplate = renderAsMarkdown ? SINGLE_STRATEGY_PROMPT_TEMPLATE : SINGLE_STRATEGY_PLAIN_PROMPT_TEMPLATE;
        Optional<String> toolLanguage = acronymToolLanguage(targetLanguage);
        List<String> translated = new ArrayList<>(pageImages.size());
        for (byte[] imageBytes : pageImages) {
            Media media = imagePreprocessor.toMedia(imageBytes);
            String result = call(visionClient, buildPrompt(promptTemplate, targetLanguage, ""), media, toolLanguage);
            translated.add(result != null ? result : "");
        }
        return translated;
    }

    /**
     * Translates a single plain text in one blocking (non-streamed) LLM call.
     */
    public String translateText(String text, TargetLanguage targetLanguage) {
        String result = call(llmClient, buildPrompt(TRANSLATE_PLAIN_PROMPT_TEMPLATE, targetLanguage, text), null,
                acronymToolLanguage(targetLanguage));
        return result != null ? result : "";
    }

    /**
     * Streams the translation of a single text page token-by-token.
     */
    public Flux<String> translatePageStream(String extractedText, TargetLanguage targetLanguage) {
        return stream(llmClient, buildPrompt(TRANSLATE_PLAIN_PROMPT_TEMPLATE, targetLanguage, extractedText), null,
                acronymToolLanguage(targetLanguage));
    }

    /**
     * Streams the translation of a single image page (single strategy) token-by-token.
     */
    public Flux<String> translatePageSingleStrategyStream(byte[] imageBytes, TargetLanguage targetLanguage) {
        Media media = imagePreprocessor.toMedia(imageBytes);
        return stream(visionClient, buildPrompt(SINGLE_STRATEGY_PLAIN_PROMPT_TEMPLATE, targetLanguage, ""), media,
                acronymToolLanguage(targetLanguage));
    }

    /**
     * One translation call, with the acronym tool when enabled. When the model goes over the lookup cap (it keeps
     * calling the tool instead of translating), the translation is made again once, without the tool.
     */
    private String call(ChatClient client, String prompt, Media media, Optional<String> toolLanguage) {
        if (toolLanguage.isEmpty()) {
            return request(client, prompt, media).call().content();
        }
        try {
            return withAcronymTool(request(client, withToolInstruction(prompt, toolLanguage), media), toolLanguage)
                    .call()
                    .content();
        } catch (RuntimeException exception) {
            if (!isToolCallLimitExceeded(exception)) {
                throw exception;
            }
            logToolLimitFallback(exception);
            return request(client, prompt, media).call().content();
        }
    }

    /** Streaming counterpart of {@link #call}: the tool rounds stream no token, so the fallback starts clean. */
    private Flux<String> stream(ChatClient client, String prompt, Media media, Optional<String> toolLanguage) {
        if (toolLanguage.isEmpty()) {
            return request(client, prompt, media).stream().content();
        }
        return withAcronymTool(request(client, withToolInstruction(prompt, toolLanguage), media), toolLanguage)
                .stream()
                .content()
                .onErrorResume(TextTranslationService::isToolCallLimitExceeded, exception -> {
                    logToolLimitFallback(exception);
                    return request(client, prompt, media).stream().content();
                });
    }

    private static ChatClient.ChatClientRequestSpec request(ChatClient client, String prompt, Media media) {
        if (media == null) {
            return client.prompt().user(prompt);
        }
        UserMessage message = UserMessage.builder()
                .text(prompt)
                .media(media)
                .build();
        return client.prompt().messages(message);
    }

    static boolean isToolCallLimitExceeded(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ToolCallLimitReachedException || cause instanceof ToolCallLimitExceededException) {
                return true;
            }
        }
        return false;
    }

    private static void logToolLimitFallback(Throwable exception) {
        log.warn("The model went over the {} lookup cap ({}): translating again without the tool",
                AcronymLookupTool.TOOL_NAME, exception.getMessage());
    }

    static String buildPrompt(String template, TargetLanguage targetLanguage, String text) {
        String languageInstruction = LANGUAGE_INSTRUCTION.formatted(targetLanguage.promptLabel(), targetLanguage.englishName());
        return template.formatted(targetLanguage.promptLabel(), targetLanguage.englishName(), languageInstruction, text);
    }

    /**
     * The glossary language code of the target language when the tool is enabled, empty otherwise.
     * Every {@link TargetLanguage} (fr, de, it, en) is a glossary language.
     */
    private Optional<String> acronymToolLanguage(TargetLanguage targetLanguage) {
        return acronymLookupTool.map(tool -> targetLanguage.code());
    }

    /**
     * Inserts the acronym instruction after the task, before the text to translate (text prompts have a blank
     * line before "Text to translate into …"); image prompts have no text part, so it goes at the end.
     */
    private static String withToolInstruction(String prompt, Optional<String> toolLanguage) {
        if (toolLanguage.isEmpty()) {
            return prompt;
        }
        int endOfTask = prompt.indexOf("\n\n");
        return endOfTask < 0
                ? prompt + "\n" + ACRONYM_TOOL_INSTRUCTION
                : prompt.substring(0, endOfTask) + "\n\n" + ACRONYM_TOOL_INSTRUCTION + "\n" + prompt.substring(endOfTask + 2);
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
