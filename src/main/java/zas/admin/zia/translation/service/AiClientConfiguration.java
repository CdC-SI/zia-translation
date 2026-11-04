package zas.admin.zia.translation.service;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallLimitBehavior;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import zas.admin.zia.translation.service.glossary.AcronymLookupTool;

import java.util.ArrayList;
import java.util.List;

@Configuration
class AiClientConfiguration {

    private static final String OPENAI_API_VERSION_PATH = "/v1";

    @Bean("visionChatClient")
    ChatClient visionChatClient(
            @Value("${zia.internal.vision.base-url}") String baseUrl,
            @Value("${zia.internal.vision.model}") String model,
            @Value("${zia.internal.api-key}") String apiKey,
            @Value("${zia.internal.vision.max-tokens:8192}") int maxTokens,
            @Value("${zia.translation.glossary.max-tool-calls:2}") int maxToolCalls,
            @Value("${zia.internal.prompt-size-logging.enabled:false}") boolean promptSizeLogging,
            ObservationRegistry observationRegistry) {
        return buildChatClient(baseUrl, model, apiKey, maxTokens, maxToolCalls, promptSizeLogging, observationRegistry);
    }

    @Bean("llmChatClient")
    ChatClient llmChatClient(
            @Value("${zia.internal.llm.base-url}") String baseUrl,
            @Value("${zia.internal.llm.model}") String model,
            @Value("${zia.internal.api-key}") String apiKey,
            @Value("${zia.internal.llm.max-tokens:8192}") int maxTokens,
            @Value("${zia.translation.glossary.max-tool-calls:2}") int maxToolCalls,
            @Value("${zia.internal.prompt-size-logging.enabled:false}") boolean promptSizeLogging,
            ObservationRegistry observationRegistry) {
        return buildChatClient(baseUrl, model, apiKey, maxTokens, maxToolCalls, promptSizeLogging, observationRegistry);
    }

    private static ChatClient buildChatClient(String baseUrl, String model, String apiKey, int maxTokens,
                                              int maxToolCalls, boolean promptSizeLogging,
                                              ObservationRegistry observationRegistry) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .baseUrl(toOpenAiSdkBaseUrl(baseUrl))
                .apiKey(apiKey)
                .model(model)
                // Reasoning and answer together: stops a generation that loops instead of running to the
                // context limit (a page needs ~1,000 tokens). A capped answer fails in FinishReasonAdvisor.
                .maxTokens(maxTokens)
                .build();
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .options(options)
                .observationRegistry(observationRegistry)
                .build();
        // ChatClient runs the tools itself, in its ToolCallingAdvisor: the manager must be given there, not to the model.
        return ChatClient.builder(chatModel, observationRegistry, null, null,
                        ToolCallingAdvisor.builder().toolCallingManager(toolCallingManager(maxToolCalls, observationRegistry)))
                .defaultAdvisors(advisors(promptSizeLogging))
                .build();
    }

    static List<Advisor> advisors(boolean promptSizeLogging) {
        List<Advisor> advisors = new ArrayList<>(List.of(new ToolCallLimitAdvisor(), new FinishReasonAdvisor()));
        if (promptSizeLogging) {
            advisors.add(new PromptSizeLoggingAdvisor());
        }
        return advisors;
    }

    /**
     * Caps the acronym lookups per translation request. Spring AI's default is 40 calls per tool, and on 20.10
     * the model used them all checking its own target-language forms. Beyond the cap the tool loop ends and
     * ToolCallLimitAdvisor fails the request; TextTranslationService then translates again without the tool.
     * <p>
     * RETURN_ERROR_RESPONSE is not an option: it only skips the execution, and the ToolCallingAdvisor loops as long
     * as the model asks for a tool. A model repeating the same call then grew the prompt to the context limit
     * (234 rounds seen on 04.11).
     */
    static ToolCallingManager toolCallingManager(int maxToolCalls, ObservationRegistry observationRegistry) {
        return DefaultToolCallingManager.builder()
                .observationRegistry(observationRegistry)
                .maxCallsPerTool(AcronymLookupTool.TOOL_NAME, maxToolCalls)
                .onLimitExceeded(ToolCallLimitBehavior.THROW)
                .build();
    }

    /**
     * Configured base URLs are host roots (e.g. {@code http://host:port}); the OpenAI SDK expects
     * the URL to include the API version segment, as it only appends {@code /chat/completions}.
     */
    static String toOpenAiSdkBaseUrl(String baseUrl) {
        String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return trimmed.endsWith(OPENAI_API_VERSION_PATH) ? trimmed : trimmed + OPENAI_API_VERSION_PATH;
    }
}
