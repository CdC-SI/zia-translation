package zas.admin.zia.translation.service;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import zas.admin.zia.translation.service.glossary.AcronymLookupTool;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;

class AiClientConfigurationTest {

    private static final String COMPLETION_RESPONSE = """
            {"id":"chatcmpl-1","object":"chat.completion","created":1,"model":"test-model",
             "choices":[{"index":0,"message":{"role":"assistant","content":"bonjour"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
            """;

    /** A model that asks for lookup_acronyms on every turn, like the loop seen in the dev logs (20.10). */
    private static final String TOOL_CALL_RESPONSE = """
            {"id":"chatcmpl-%1$d","object":"chat.completion","created":1,"model":"test-model",
             "choices":[{"index":0,"message":{"role":"assistant","content":null,"tool_calls":[
               {"id":"call_%1$d","type":"function","function":{"name":"lookup_acronyms",
                "arguments":"{\\"acronyms\\":[\\"AFam\\"],\\"sourceLanguage\\":\\"FR\\"}"}}]},
               "finish_reason":"tool_calls"}],
             "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
            """;

    private final AtomicReference<String> requestPath = new AtomicReference<>();
    private final AtomicReference<String> authorizationHeader = new AtomicReference<>();
    private final List<String> requestBodies = new CopyOnWriteArrayList<>();
    private final AtomicReference<IntFunction<String>> responder = new AtomicReference<>(n -> COMPLETION_RESPONSE);
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            requestPath.set(exchange.getRequestURI().getPath());
            authorizationHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = responder.get().apply(requestBodies.size()).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void chatClientCallsOpenAiCompatibleEndpointUnderV1() {
        ChatClient client = new AiClientConfiguration()
                .llmChatClient(baseUrl(), "test-model", "secret-key", 8192, 2, ObservationRegistry.NOOP);

        String content = client.prompt().user("hello").call().content();

        assertThat(content).isEqualTo("bonjour");
        assertThat(requestPath.get()).isEqualTo("/v1/chat/completions");
        assertThat(authorizationHeader.get()).isEqualTo("Bearer secret-key");
        assertThat(requestBodies.getLast()).contains("\"model\":\"test-model\"");
    }

    @Test
    void requestsCarryTheMaxTokensCap() {
        ChatClient client = new AiClientConfiguration()
                .visionChatClient(baseUrl(), "test-model", "secret-key", 8192, 2, ObservationRegistry.NOOP);

        client.prompt().user("hello").call().content();

        assertThat(requestBodies.getLast()).containsPattern("\"max_(completion_)?tokens\":8192");
    }

    @Test
    void acronymLookupsAreCappedPerRequest() {
        // The fake model asks for the tool 5 times, then answers. Only the first 2 calls may run; the others
        // must get the limit message as their tool result, without failing the request.
        responder.set(n -> n <= 5 ? TOOL_CALL_RESPONSE.formatted(n) : COMPLETION_RESPONSE);
        CountingLookupTool tool = new CountingLookupTool();
        ChatClient client = new AiClientConfiguration()
                .visionChatClient(baseUrl(), "test-model", "secret-key", 8192, 2, ObservationRegistry.NOOP);

        String content = client.prompt().user("Les AFam sont versées.").tools(tool).call().content();

        assertThat(content).isEqualTo("bonjour");
        assertThat(tool.executions.get()).isEqualTo(2);
        assertThat(requestBodies.getLast()).contains("limit");
    }

    @Test
    void baseUrlIsNormalizedToIncludeApiVersion() {
        assertThat(AiClientConfiguration.toOpenAiSdkBaseUrl("http://host:8000")).isEqualTo("http://host:8000/v1");
        assertThat(AiClientConfiguration.toOpenAiSdkBaseUrl("http://host:8000/")).isEqualTo("http://host:8000/v1");
        assertThat(AiClientConfiguration.toOpenAiSdkBaseUrl("http://host:8000/v1")).isEqualTo("http://host:8000/v1");
        assertThat(AiClientConfiguration.toOpenAiSdkBaseUrl("http://host:8000/v1/")).isEqualTo("http://host:8000/v1");
    }

    private String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    /** Stands in for AcronymLookupTool: same name, counts how often Spring AI really runs it. */
    static class CountingLookupTool {
        final AtomicInteger executions = new AtomicInteger();

        @Tool(name = AcronymLookupTool.TOOL_NAME, description = "test lookup")
        public String lookupAcronyms(@ToolParam(description = "acronyms") List<String> acronyms,
                                     @ToolParam(description = "language") String sourceLanguage) {
            executions.incrementAndGet();
            return "found";
        }
    }
}
