package zas.admin.zia.translation.service;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.support.RetryTemplate;

import java.util.Map;

@Configuration
class AiClientConfiguration {

    @Bean("visionChatClient")
    ChatClient visionChatClient(
            @Value("${zia.internal.vision.base-url}") String baseUrl,
            @Value("${zia.internal.vision.model}") String model,
            @Value("${zia.internal.api-key}") String apiKey,
            ObservationRegistry observationRegistry) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(model)
                .reasoningEffort("high")
                .extraBody(Map.of("thinking", Map.of("type", "enabled")))
                .build();
        return ChatClient.create(buildChatModel(baseUrl, apiKey, options, observationRegistry));
    }

    @Bean("llmChatClient")
    ChatClient llmChatClient(
            @Value("${zia.internal.llm.base-url}") String baseUrl,
            @Value("${zia.internal.llm.model}") String model,
            @Value("${zia.internal.api-key}") String apiKey,
            ObservationRegistry observationRegistry) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(model)
                .build();
        return ChatClient.create(buildChatModel(baseUrl, apiKey, options, observationRegistry));
    }

    private static OpenAiChatModel buildChatModel(String baseUrl, String apiKey, OpenAiChatOptions options,
                                                   ObservationRegistry observationRegistry) {
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build();
        ToolCallingManager toolCallingManager = ToolCallingManager.builder()
                .observationRegistry(observationRegistry)
                .build();
        RetryTemplate retryTemplate = new RetryTemplate();
        return new OpenAiChatModel(api, options, toolCallingManager, retryTemplate, observationRegistry);
    }
}
