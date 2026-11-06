package zas.admin.zia.translation.service;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.ToolCallLimitExceededException;
import reactor.core.publisher.Flux;

/**
 * Fails a request whose tool loop was ended by the tool-call cap. With {@code ToolCallLimitBehavior.THROW},
 * Spring AI's ToolCallingAdvisor catches the limit exception and returns a generation with finish reason
 * {@code toolCallLimitExceeded} whose text is the limit message or the last tool result: callers reading only
 * {@code .content()} would take it for the translation.
 * <p>
 * Ordered outside the tool-calling loop, to see the answer ToolCallingAdvisor returns.
 */
class ToolCallLimitAdvisor implements CallAdvisor, StreamAdvisor {

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientResponse response = chain.nextCall(request);
        checkFinishReason(response);
        return response;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return chain.nextStream(request).doOnNext(ToolCallLimitAdvisor::checkFinishReason);
    }

    private static void checkFinishReason(ChatClientResponse response) {
        ChatResponse chatResponse = response.chatResponse();
        if (chatResponse == null) {
            return;
        }
        for (Generation generation : chatResponse.getResults()) {
            if (generation.getMetadata() != null && ToolCallLimitExceededException.FINISH_REASON
                    .equalsIgnoreCase(generation.getMetadata().getFinishReason())) {
                throw new ToolCallLimitReachedException("The model went over its tool-call cap instead of answering");
            }
        }
    }

    @Override
    public String getName() {
        return "ToolCallLimitAdvisor";
    }

    @Override
    public int getOrder() {
        return ToolCallingAdvisor.DEFAULT_ORDER - 1;
    }
}
