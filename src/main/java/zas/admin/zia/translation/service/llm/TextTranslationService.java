package zas.admin.zia.translation.service.llm;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
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
            Acronyms: before translating, call the lookup_acronyms tool once with every acronym of the source text and the language of the source text. Web and e-mail addresses are not acronyms. Currency codes, units and article numbers are not acronyms.
            - If the tool finds an acronym and one candidate's full name or definition matches its meaning in this text, write one of that candidate's targetForms at every occurrence of the acronym, also inside compound words.
            - If no candidate matches its meaning in this text, the acronym means something else here: translate it as you normally would.
            - If the tool does not find an acronym, translate it as you normally would
            """;

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
            String prompt = withToolInstruction(buildPrompt(template, targetLanguage, pageText), toolLanguage);
            String result = withAcronymTool(llmClient.prompt().user(prompt), toolLanguage)
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
        Optional<String> toolLanguage = acronymToolLanguage(targetLanguage);
        List<String> translated = new ArrayList<>(pageImages.size());
        for (byte[] imageBytes : pageImages) {
            String prompt = withToolInstruction(buildPrompt(promptTemplate, targetLanguage, ""), toolLanguage);
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
    public String translateText(String text, TargetLanguage targetLanguage) {
        Optional<String> toolLanguage = acronymToolLanguage(targetLanguage);
        String prompt = withToolInstruction(buildPrompt(TRANSLATE_PLAIN_PROMPT_TEMPLATE, targetLanguage, text), toolLanguage);
        String result = withAcronymTool(llmClient.prompt().user(prompt), toolLanguage)
                .call()
                .content();
        return result != null ? result : "";
    }

    /**
     * Streams the translation of a single text page token-by-token.
     */
    public Flux<String> translatePageStream(String extractedText, TargetLanguage targetLanguage) {
        Optional<String> toolLanguage = acronymToolLanguage(targetLanguage);
        String prompt = withToolInstruction(buildPrompt(TRANSLATE_PLAIN_PROMPT_TEMPLATE, targetLanguage, extractedText), toolLanguage);
        return withAcronymTool(llmClient.prompt().user(prompt), toolLanguage)
                .stream()
                .content();
    }

    /**
     * Streams the translation of a single image page (single strategy) token-by-token.
     */
    public Flux<String> translatePageSingleStrategyStream(byte[] imageBytes, TargetLanguage targetLanguage) {
        Optional<String> toolLanguage = acronymToolLanguage(targetLanguage);
        String prompt = withToolInstruction(buildPrompt(SINGLE_STRATEGY_PLAIN_PROMPT_TEMPLATE, targetLanguage, ""), toolLanguage);
        Media media = imagePreprocessor.toMedia(imageBytes);
        UserMessage message = UserMessage.builder()
                .text(prompt)
                .media(media)
                .build();
        return withAcronymTool(visionClient.prompt().messages(message), toolLanguage)
                .stream()
                .content();
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
