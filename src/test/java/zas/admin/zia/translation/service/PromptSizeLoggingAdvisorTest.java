package zas.admin.zia.translation.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PromptSizeLoggingAdvisorTest {

    @Test
    void firstCallOfARequestIsRoundOne() {
        PromptSizeLoggingAdvisor.PromptStats stats = PromptSizeLoggingAdvisor.PromptStats.of(List.of(
                new SystemMessage("sys"), new UserMessage("hello")));

        assertThat(stats.round()).isEqualTo(1);
        assertThat(stats.messageCount()).isEqualTo(2);
        assertThat(stats.systemChars()).isEqualTo(3);
        assertThat(stats.userChars()).isEqualTo(5);
        assertThat(stats.totalChars()).isEqualTo(8);
        assertThat(stats.toolCalls()).isZero();
    }

    @Test
    void eachToolRoundAddsAssistantAndToolMessages() {
        AssistantMessage.ToolCall call = new AssistantMessage.ToolCall("c1", "function", "lookup_acronyms", "{\"a\":1}");
        AssistantMessage toolCallMessage = AssistantMessage.builder().content("").toolCalls(List.of(call)).build();
        ToolResponseMessage found = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("c1", "lookup_acronyms", "[result]")))
                .build();
        ToolResponseMessage limit = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("c2", "lookup_acronyms",
                        "Tool call limit (2) exceeded for tool 'lookup_acronyms'.")))
                .build();

        PromptSizeLoggingAdvisor.PromptStats stats = PromptSizeLoggingAdvisor.PromptStats.of(List.of(
                new UserMessage("hello"), toolCallMessage, found, toolCallMessage, limit));

        assertThat(stats.round()).isEqualTo(3);
        assertThat(stats.toolCalls()).isEqualTo(2);
        assertThat(stats.toolResults()).isEqualTo(2);
        assertThat(stats.limitErrors()).isEqualTo(1);
        assertThat(stats.assistantChars()).isEqualTo(2 * ("lookup_acronyms".length() + "{\"a\":1}".length()));
        assertThat(stats.toolChars()).isEqualTo("[result]".length()
                + "Tool call limit (2) exceeded for tool 'lookup_acronyms'.".length());
    }
}
