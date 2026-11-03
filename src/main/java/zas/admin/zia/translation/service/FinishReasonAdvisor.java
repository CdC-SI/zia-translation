package zas.admin.zia.translation.service;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

/**
 * Fails an answer cut off by the max-tokens cap. The server does not return an error then: it answers with finish
 * reason {@code length} and partial or empty content (empty when the cap is reached while reasoning), and callers
 * that read only {@code .content()} would take a truncated OCR or translation for a complete one.
 * <p>
 * Ordered just before the model call, inside the tool-calling loop: every model answer is checked, the
 * intermediate tool-call answers included.
 */
class FinishReasonAdvisor implements CallAdvisor, StreamAdvisor {

    // Spring AI's OpenAI model reports the SDK enum name (LENGTH), not the raw API value (length).
    private static final String LENGTH = "length";

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientResponse response = chain.nextCall(request);
        checkFinishReason(response);
        return response;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        // The finish reason comes with the last chunk: the tokens already streamed are followed by the error.
        return chain.nextStream(request).doOnNext(FinishReasonAdvisor::checkFinishReason);
    }

    private static void checkFinishReason(ChatClientResponse response) {
        ChatResponse chatResponse = response.chatResponse();
        if (chatResponse == null) {
            return;
        }
        for (Generation generation : chatResponse.getResults()) {
            if (generation.getMetadata() != null
                    && LENGTH.equalsIgnoreCase(generation.getMetadata().getFinishReason())) {
                throw new IncompleteGenerationException(
                        "The model reached the max-tokens cap before finishing its answer (finish reason length)");
            }
        }
    }

    @Override
    public String getName() {
        return "FinishReasonAdvisor";
    }

    @Override
    public int getOrder() {
        // ChatModelCallAdvisor and ChatModelStreamAdvisor, which call the model, are at LOWEST_PRECEDENCE.
        return Ordered.LOWEST_PRECEDENCE - 1;
    }
}
