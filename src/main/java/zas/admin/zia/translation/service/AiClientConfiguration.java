package zas.admin.zia.translation.service;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class AiClientConfiguration {

    private static final String OPENAI_API_VERSION_PATH = "/v1";

    @Bean("visionChatClient")
    ChatClient visionChatClient(
            @Value("${zia.internal.vision.base-url}") String baseUrl,
            @Value("${zia.internal.vision.model}") String model,
            @Value("${zia.internal.api-key}") String apiKey,
            ObservationRegistry observationRegistry) {
        return ChatClient.create(buildChatModel(baseUrl, model, apiKey, observationRegistry));
    }

    @Bean("llmChatClient")
    ChatClient llmChatClient(
            @Value("${zia.internal.llm.base-url}") String baseUrl,
            @Value("${zia.internal.llm.model}") String model,
            @Value("${zia.internal.api-key}") String apiKey,
            ObservationRegistry observationRegistry) {
        return ChatClient.create(buildChatModel(baseUrl, model, apiKey, observationRegistry));
    }

    private static OpenAiChatModel buildChatModel(String baseUrl, String model, String apiKey,
                                                   ObservationRegistry observationRegistry) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .baseUrl(toOpenAiSdkBaseUrl(baseUrl))
                .apiKey(apiKey)
                .model(model)
                .build();
        return OpenAiChatModel.builder()
                .options(options)
                .observationRegistry(observationRegistry)
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
