package zas.admin.zia.translation.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallLimitBehavior;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.micrometer.observation.ObservationRegistry;
import zas.admin.zia.translation.service.glossary.AcronymLookupTool;

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
            ObservationRegistry observationRegistry) {
        return buildChatClient(baseUrl, model, apiKey, maxTokens, maxToolCalls, observationRegistry);
    }

    @Bean("llmChatClient")
    ChatClient llmChatClient(
            @Value("${zia.internal.llm.base-url}") String baseUrl,
            @Value("${zia.internal.llm.model}") String model,
            @Value("${zia.internal.api-key}") String apiKey,
            @Value("${zia.internal.llm.max-tokens:8192}") int maxTokens,
            @Value("${zia.translation.glossary.max-tool-calls:2}") int maxToolCalls,
            ObservationRegistry observationRegistry) {
        return buildChatClient(baseUrl, model, apiKey, maxTokens, maxToolCalls, observationRegistry);
    }

    private static ChatClient buildChatClient(String baseUrl, String model, String apiKey, int maxTokens,
                                              int maxToolCalls, ObservationRegistry observationRegistry) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .baseUrl(toOpenAiSdkBaseUrl(baseUrl))
                .apiKey(apiKey)
                .model(model)
                // Reasoning and answer together: stops a generation that loops instead of running to the
                // context limit (a page needs ~1,000 tokens).
                .maxTokens(maxTokens)
                .build();
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .options(options)
                .observationRegistry(observationRegistry)
                .build();
        // ChatClient runs the tools itself, in its ToolCallingAdvisor: the manager must be given there, not to the model.
        return ChatClient.builder(chatModel, observationRegistry, null, null,
                        ToolCallingAdvisor.builder().toolCallingManager(toolCallingManager(maxToolCalls, observationRegistry)))
                .build();
    }

    /**
     * Caps the acronym lookups per translation request. Spring AI's default is 40 calls per tool, and on 20.10
     * the model used them all checking its own target-language forms. Beyond the cap the call is not run: the
     * model receives an error message as the tool result and goes on with the translation.
     */
    static ToolCallingManager toolCallingManager(int maxToolCalls, ObservationRegistry observationRegistry) {
        return DefaultToolCallingManager.builder()
                .observationRegistry(observationRegistry)
                .maxCallsPerTool(AcronymLookupTool.TOOL_NAME, maxToolCalls)
                .onLimitExceeded(ToolCallLimitBehavior.RETURN_ERROR_RESPONSE)
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
