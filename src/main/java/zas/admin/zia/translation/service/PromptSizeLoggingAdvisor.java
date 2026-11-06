package zas.admin.zia.translation.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Diagnostic: logs the size of every prompt sent to the model and the usage it reports. Added to the chat clients
 * only when {@code zia.internal.prompt-size-logging.enabled=true}. Ordered inside the tool-calling loop
 * (like {@link FinishReasonAdvisor}): each round of the loop is logged, so a prompt that grows with tool calls
 * shows up round after round. Sizes and counts go to INFO; the tool-call arguments (fragments of the submitted
 * text) go to DEBUG only.
 */
class PromptSizeLoggingAdvisor implements CallAdvisor, StreamAdvisor {

    private static final Logger log = LoggerFactory.getLogger(PromptSizeLoggingAdvisor.class);

    /** Start of the message DefaultToolCallingManager returns as tool result once a limit is reached. */
    private static final String LIMIT_EXCEEDED_MARKER = "limit (";

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        PromptStats stats = PromptStats.of(request.prompt().getInstructions());
        logRequest(stats);
        ChatClientResponse response = chain.nextCall(request);
        logResponse(stats.round(), response);
        return response;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        PromptStats stats = PromptStats.of(request.prompt().getInstructions());
        logRequest(stats);
        // Usage and finish reason come with the last chunk(s): log only the chunks that carry them.
        return chain.nextStream(request).doOnNext(response -> {
            if (usage(response) != null || hasToolCalls(response)) {
                logResponse(stats.round(), response);
            }
        });
    }

    private static void logRequest(PromptStats stats) {
        log.info("LLM call round {}: {} messages, {} chars (~{} tokens) [system={}, user={}, assistant={}, tool={}], "
                        + "{} tool calls so far, {} tool results, {} limit errors",
                stats.round(), stats.messageCount(), stats.totalChars(), stats.totalChars() / 4,
                stats.systemChars(), stats.userChars(), stats.assistantChars(), stats.toolChars(),
                stats.toolCalls(), stats.toolResults(), stats.limitErrors());
    }

    private static void logResponse(int round, ChatClientResponse response) {
        Usage usage = usage(response);
        List<AssistantMessage.ToolCall> toolCalls = toolCalls(response);
        log.info("LLM answer round {}: promptTokens={}, completionTokens={}, finishReason={}, {} tool calls requested, "
                        + "{} answer chars",
                round,
                usage != null ? usage.getPromptTokens() : null,
                usage != null ? usage.getCompletionTokens() : null,
                finishReason(response), toolCalls.size(), answerChars(response));
        if (log.isDebugEnabled()) {
            toolCalls.forEach(call -> log.debug("LLM answer round {}: tool call {} {}", round, call.name(), call.arguments()));
        }
    }

    private static Usage usage(ChatClientResponse response) {
        ChatResponse chatResponse = response.chatResponse();
        if (chatResponse == null || chatResponse.getMetadata() == null) {
            return null;
        }
        Usage usage = chatResponse.getMetadata().getUsage();
        return usage == null || usage.getPromptTokens() == null || usage.getPromptTokens() == 0 ? null : usage;
    }

    private static boolean hasToolCalls(ChatClientResponse response) {
        return !toolCalls(response).isEmpty();
    }

    private static List<AssistantMessage.ToolCall> toolCalls(ChatClientResponse response) {
        return generations(response).stream()
                .map(Generation::getOutput)
                .filter(output -> output != null && output.getToolCalls() != null)
                .flatMap(output -> output.getToolCalls().stream())
                .toList();
    }

    private static String finishReason(ChatClientResponse response) {
        return generations(response).stream()
                .map(Generation::getMetadata)
                .filter(metadata -> metadata != null && metadata.getFinishReason() != null)
                .map(metadata -> metadata.getFinishReason())
                .findFirst()
                .orElse(null);
    }

    private static int answerChars(ChatClientResponse response) {
        return generations(response).stream()
                .map(Generation::getOutput)
                .filter(output -> output != null && output.getText() != null)
                .mapToInt(output -> output.getText().length())
                .sum();
    }

    private static List<Generation> generations(ChatClientResponse response) {
        ChatResponse chatResponse = response.chatResponse();
        return chatResponse == null ? List.of() : chatResponse.getResults();
    }

    @Override
    public String getName() {
        return "PromptSizeLoggingAdvisor";
    }

    @Override
    public int getOrder() {
        // Inside the tool-calling loop, just before FinishReasonAdvisor (LOWEST_PRECEDENCE - 1).
        return Ordered.LOWEST_PRECEDENCE - 2;
    }

    /**
     * Size of a prompt. {@code round} is 1 for the first call of a request, then +1 per tool-calling round
     * (each round adds one assistant message with the tool calls and one tool message with their results).
     * Characters / 4 is only a rough token estimate; the exact count is the promptTokens of the answer.
     */
    record PromptStats(int round, int messageCount, int systemChars, int userChars, int assistantChars, int toolChars,
                       int toolCalls, int toolResults, int limitErrors) {

        static PromptStats of(List<Message> messages) {
            int assistantMessages = 0;
            int system = 0;
            int user = 0;
            int assistant = 0;
            int tool = 0;
            int toolCalls = 0;
            int toolResults = 0;
            int limitErrors = 0;
            for (Message message : messages) {
                switch (message) {
                    case AssistantMessage assistantMessage -> {
                        assistantMessages++;
                        assistant +=  length(assistantMessage.getText());
                        for (AssistantMessage.ToolCall call : assistantMessage.getToolCalls()) {
                            toolCalls++;
                            assistant += length(call.name()) + length(call.arguments());
                        }
                    }
                    case ToolResponseMessage toolMessage -> {
                        for (ToolResponseMessage.ToolResponse toolResponse : toolMessage.getResponses()) {
                            toolResults++;
                            tool += length(toolResponse.responseData());
                            if (toolResponse.responseData() != null
                                    && toolResponse.responseData().contains(LIMIT_EXCEEDED_MARKER)) {
                                limitErrors++;
                            }
                        }
                    }
                    default -> {
                        if (message.getMessageType() == MessageType.SYSTEM) {
                            system += length(message.getText());
                        } else {
                            user += length(message.getText());
                        }
                    }
                }
            }
            return new PromptStats(assistantMessages + 1, messages.size(), system, user, assistant, tool, toolCalls, toolResults, limitErrors);
        }

        int totalChars() {
            return systemChars + userChars + assistantChars + toolChars;
        }

        private static int length(String text) {
            return text == null ? 0 : text.length();
        }
    }
}
